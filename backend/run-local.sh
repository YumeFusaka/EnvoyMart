#!/usr/bin/env bash
#
# 本地一键启动全部后端服务（Windows + Git Bash / Linux / macOS 通用）。
#
# 为什么需要它：JWT_SECRET 必须让每个服务拿到<b>同一个值</b>。文档原先教的是
# "在每个终端里 export JWT_SECRET=\"$(openssl rand -base64 48)\""——每执行一次就换一个
# 随机值，于是分别启动的 gateway 与 auth-service 各拿到一个不同的密钥。症状是
# 「登录成功，但之后所有接口 401」，而 token 本身完全正常（换到 ai-service 的 MCP 端点
# 甚至能验签通过），排查成本极高。
#
# 这里统一从 backend/.env.local 读，并在启动日志里打印密钥指纹——两边指纹一致即说明配对了。
#
# 用法:
#   ./run-local.sh                   启动全部
#   ./run-local.sh dev               开发环境一键启动：中间件 + 全部服务 + 前端 dev server（支持热更新）
#   ./run-local.sh master            演示环境一键启动：中间件 + 全部服务 + 前端生产产物（无热更新，首屏快、画面干净）
#   ./run-local.sh demo              master 的旧名，保留为别名（脚本与文档里仍有引用）
#   ./run-local.sh stop              停止全部（含前端；中间件容器保持运行）
#   ./run-local.sh stop ai-service   只停止指定的一个或多个
#   ./run-local.sh auth-service      只启动指定的一个或多个
#
# dev 与 master 共用同一套启动流程，只有前端那一段不同：
#   dev    → pnpm dev（5173，HMR，改前端源码即时生效；改后端仍需重启对应服务）
#   master → pnpm build + pnpm preview（5173，生产产物，无 HMR，适合演示/投屏/验收）
# 两者都占用 5173，不能同时起；已在跑时脚本会跳过前端那一步。
#
set -uo pipefail
cd "$(dirname "$0")"

# 双击 .sh 启动时 bash 跑在交互模式下（`bash --login -i run-local.sh demo`），
# 于是 Git for Windows 自带的 /etc/profile.d/aliases.sh 在 mintty（TERM=xterm*）下
# 会把 node 包成 `winpty node.exe` —— 那是给交互式 REPL 用的。而本脚本的 node 调用
# 全都带着输出重定向（中间件握手、派生数据重建），winpty 找不到 tty 就直接失败或挂死，
# 且 stderr 被 `2>/dev/null` 吞掉：症状是「curl 版检查全过、node 版检查全不过」
# （nacos/ES/milvus 就绪，mysql/redis/rabbitmq 全未就绪），或整个脚本冻在某一项检查上。
# 同一份检查在非交互 shell 里完全正常——脚本要的就是非交互行为，这里显式去掉 alias。
unalias node 2>/dev/null || true

# 两种运行场景要区别对待：
#   双击 .sh → bash 是交互式（bash --login -i run-local.sh demo），脚本一结束窗口就关；
#   终端里跑 → 非交互，脚本结束把命令行交还给终端。
# 交互场景下把「窗口」做成演示环境的总开关（见 hold_window / stop_all_fast）：关窗即停服务。
INTERACTIVE=""
case $- in *i*) INTERACTIVE=1 ;; esac

ENV_FILE=.env.local
LOG_DIR=../logs/logs-local

# 链路追踪（可选）：agent 由 `docker cp` 从官方镜像提取，不入库（见 .gitignore）
#
# **必须落到纯 ASCII 路径**：项目所在目录名含中文，而 `-javaagent` 的参数在 Windows 上
# 过一遍 Maven 的 JVM 参数拼接后，非 ASCII 字符变成乱码，JVM 报
# "Error opening zip file or JAR manifest missing"——用相对路径也不行，因为
# spring-boot:run fork 出的 JVM 工作目录并不是脚本目录；cygpath 的 8.3 短名也救不了，
# 上级目录「面试训练」没有生成短名。所以启动前把 agent 同步到用户目录（纯 ASCII）。
AGENT_SRC="$(cd .. && pwd)/skywalking-agent"
AGENT_HOME="$HOME/.envoymart/skywalking-agent"
# 用 ENVOYMART_SKYWALKING=off 可以关掉追踪——它给每个方法插桩，压测时要量的
# 本来就是"除掉观测之后还剩多少"，关掉才能拿到干净数字。
#
# 但**它不影响正确性**。曾经以为"开了追踪并发下单就从 10 单掉到 3 单"，
# 排查到最后发现是 StockConcurrencyTest 自己不是幂等的（购物车跨轮次累加）。
# 修掉测试后，开/关追踪都是稳定 10 单。
if [ "${ENVOYMART_SKYWALKING:-on}" = "on" ] && [ -d "$AGENT_SRC" ]; then
  if [ ! -f "$AGENT_HOME/skywalking-agent.jar" ]; then
    mkdir -p "$AGENT_HOME"
    cp -r "$AGENT_SRC"/. "$AGENT_HOME"/
  fi
  AGENT_JAR="$(cygpath -w "$AGENT_HOME/skywalking-agent.jar" 2>/dev/null || echo "$AGENT_HOME/skywalking-agent.jar")"
else
  AGENT_JAR=""
fi
SW_OAP="${SW_OAP_ADDRESS:-127.0.0.1:11800}"

if [ ! -f "$ENV_FILE" ]; then
  cat >&2 <<'EOF'
缺少 backend/.env.local。先生成一次（之后一直复用）：

  printf 'export JWT_SECRET="%s"\n' "$(openssl rand -base64 48 | tr -d '\n')" > backend/.env.local

EOF
  exit 1
fi
set -a; source "$ENV_FILE"; set +a

# 项目要求 JDK 21，这里<b>显式指定</b>，不沿用外部 JAVA_HOME：
# 开发机上可能默认指向更低的版本（本机就是 corretto-20），而 Maven 用它来编译和运行，
# 表现为 "UnsupportedClassVersionError: class file version 65.0, this version only
# recognizes up to 64.0"——看起来像构建坏了，其实是运行时 JDK 比编译时低。
JAVA_HOME="${ENVOYMART_JAVA_HOME:-$HOME/.jdks/corretto-21.0.12}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "找不到 JDK 21：$JAVA_HOME（用 ENVOYMART_JAVA_HOME 指定其他路径）" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "使用 JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# 先把两个「库」模块装进本地仓库。
#
# 为什么必须显式做：下面用 `mvn -pl <svc> spring-boot:run` 启动单个服务，此时 reactor 里
# **只有那一个模块**，它对 common / agent-core 的依赖是从**本地仓库**解析的，不是从源码目录。
# 于是改了 common 却不 install，服务跑的还是旧 jar —— 表现为「代码明明改了，行为没变」，
# 而且全程没有任何报错。这个坑在改造中真的踩到了：改了统一异常处理器，接口返回的错误码纹丝不动。
#
# 不能改用 `-am` 把依赖拉进 reactor：spring-boot:run 会在每个被选中的模块上执行，
# 而 common / agent-core 是库、没有 main class，会直接失败。
#
# 反向的坑（2026-10-01 踩到）：install 会**重写**本地仓库里的 jar，而正在运行的服务
# 此前已打开过它。Windows 下 JVM 缓存的 jar 目录被原地覆盖后，**尚未加载**的类会报
# NoClassDefFoundError（已加载的不受影响）——症状是「服务重启后好好的，过一会儿某个
# 接口突然 500」。所以：只要重新 install 了库模块，就要把依赖它的服务一并重启，
# 不要留着旧进程继续跑。
echo "同步库模块到本地仓库（改了 contract / common / agent-core 后必须走这一步）..."
mvn -q -B install -DskipTests -pl contract,common,agent-core || {
  echo "库模块安装失败，终止启动" >&2
  exit 1
}

export DB_DRIVER="${DB_DRIVER:-com.mysql.cj.jdbc.Driver}"
# 驱动是 MySQL 时额外执行 schema-mysql.sql 做幂等加列：
# create table if not exists 不会给已存在的表补列，新加的列在老库上永远是"表里没有这一列"。
export DB_SQL_PLATFORM="${DB_SQL_PLATFORM:-mysql}"
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-3306}"
export DB_USERNAME="${DB_USERNAME:-yumefusaka}"
export DB_PASSWORD="${DB_PASSWORD:-j}"
export NACOS_ENABLED="${NACOS_ENABLED:-true}"

# 本地演示：打开模拟支付。它扮演"渠道"发回调，走的是与真实渠道完全相同的处理路径
# （含验签、终态检查、幂等），所以演示过的链路也就是真实链路。
# 生产环境不要设它 —— 这条路径能把订单标记为已支付。
export PAYMENT_MOCK_ENABLED="${PAYMENT_MOCK_ENABLED:-true}"

# 启动顺序：网关先起（它会往 Nacos 注册），其余服务随后。
#
# **knowledge-service 必须排在 ai-service 前面**：AI 服务启动时要从知识库拉全量语料
# 建检索索引，拉不到就拒绝启动（空语料的 AI 会对着每一句话回「知识库中没有相关依据」，
# 而健康检查是绿的——那比起不来更危险）。ai-service 侧有重试，所以这个顺序是优化
# 而不是硬约束；但让它先起能少等一轮重试。
# 服务就绪等待窗口（秒）。它是「从发出启动命令到最慢的服务自报就绪」的上限。
# 实测九服务冷启动各自在 40 秒内就绪（ai-service 的索引重建已改到后台，不再占启动期），
# 留 180 秒是给机器满载、首次建索引、Docker 刚起来这类情况的余量。
# 调小只影响脚本判定，不影响服务自身。
WAIT_HEALTHY_SECONDS=${WAIT_HEALTHY_SECONDS:-180}
SERVICES=(gateway-service auth-service product-service order-service knowledge-service ai-service payment-service review-service promotion-service)
# 与 SERVICES 逐位对齐，改 SERVICES 的顺序必须同步改这里——
# 错位的后果是某个服务连上别人的库，而且它能正常启动、直到第一次查表才报「表不存在」
# （库里已经有表时连这个都不会报，直接读到空数据）
DB_OF=("" envoymart_auth envoymart_product envoymart_order envoymart_knowledge "" envoymart_payment envoymart_review envoymart_promotion)

index_of() {
  local target=$1 i
  for i in "${!SERVICES[@]}"; do
    [ "${SERVICES[$i]}" = "$target" ] && { echo "$i"; return 0; }
  done
  return 1
}

# 端口<b>不在这里维护第二份</b>：它的事实源是每个服务自己的 server.port。
# 原先这里有个与 SERVICES 逐位对齐的 PORT_OF 数组，插入 knowledge-service 时忘了同步，
# 于是脚本以为 knowledge-service 在 9004（ai-service 的端口）——停止与健康检查全部指向
# 别的进程，而服务自己按 yml 绑 9008，一切看起来正常。事实源只有一个，才不会有第二处能写错。
port_of() {
  sed -n 's/^  port: *\([0-9]\{1,\}\).*/\1/p' "$1/src/main/resources/application.yml" | head -1
}

# 批量启动前的一次全量重编。
#
# 为什么要有这一步：start_one 里原本每服务各做一次 clean compile（U71 的 ECJ 桩 class 防呆），
# 九次串行实测约 113 秒——而它防的只是"单服务被单独重启时跑到旧 class"。批量启动时，
# 一次全量 clean compile 给出同样的保证（clean 删掉全部 ECJ 桩、compile 保证 class 来自本次源码），
# 代价只有一次。脚本开头已经把 contract / common / agent-core 装进本地仓库，
# 这里的目标因此是九个服务模块。
#
# 记 PRECOMPILED=1 让 start_one 跳过自己的那一遍。单独重启某个服务（./run-local.sh stop X 后
# 再 start X）时这个开关不在，单个服务仍然自带那一遍——那条路径上的防呆没有削弱。
precompile_all() {
  echo "预编译九个服务（一次全量，替代九次串行 clean compile）..."
  local t0=$SECONDS
  # 模块列表用 -pl 的逗号语法一次传入。不写成 $(IFS=,; echo ...) 那种子 shell 技巧：
  # 它的正确性依赖 IFS 在子 shell 里的展开时机，读的人第一眼看不出来它拼的是什么。
  local modules
  modules=$(printf ",%s" "${SERVICES[@]}")
  modules=${modules:1}
  if ! mvn -q -B -pl "$modules" clean compile >> "$LOG_DIR/compile-all.log" 2>&1; then
    echo "预编译失败，终止启动（详见 $LOG_DIR/compile-all.log）" >&2
    return 1
  fi
  echo "预编译完成（$((SECONDS - t0)) 秒）"
}

start_one() {
  local svc=$1 idx db
  idx=$(index_of "$svc") || { echo "未知服务: $svc" >&2; return 1; }
  db=${DB_OF[$idx]}

  local extra=()
  if [ -n "$db" ]; then
    extra+=(DB_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false")
  fi
  # AI 服务接 Milvus 作为向量库；不指定则该 profile 不生效，退回内存向量库
  [ "$svc" = "ai-service" ] && extra+=(SPRING_PROFILES_ACTIVE=milvus)

  # 链路追踪：SkyWalking javaagent 是**不改一行业务代码**就能覆盖全部服务的方式，
  # 这正是选它而不是给六个服务逐个加 OTel 依赖的原因。
  # agent 不存在时静默跳过——没装追踪不该妨碍把服务跑起来。
  local agent_args=()
  if [ -n "$AGENT_JAR" ]; then
    local sw_args="-javaagent:$AGENT_JAR -Dskywalking.agent.service_name=$svc -Dskywalking.collector.backend_service=$SW_OAP"
    # 关掉 SkyWalking 的 Neo4j 插件（插件名取自它自己的 skywalking-plugin.def）。
    #
    # 它是给 neo4j-java-driver 4.x 写的，本项目用 5.26.0。插桩之后，凡是真的写进去关系的事务
    # 都会抛 "RuntimeException: Can not do async finish for the span repeatedly."，
    # 而同一个 Cypher 在 cypher-shell 里手工跑完全正常——极易误判成 Cypher 语法或连接超时。
    # 症状是知识图谱一条边都进不去，且只发生在「有边可写」的文档上：只跑一条 DELETE 的事务
    # 反而成功，看起来像随机失败。
    #
    # 代价：Neo4j 调用不再作为独立出口 span 出现在链路里，服务级与 HTTP 链路追踪不受影响。
    # 要拿回 Neo4j span 的办法是升级插件，不是打开它——打开就是上面那条报错。
    #
    # 排除项必须走环境变量，不能写成 `-Dskywalking.plugin.exclude_plugins` ——
    # agent 读的配置键是 `plugin.exclude_plugins`（配置里展开为 `${SW_EXCLUDE_PLUGINS:}`），
    # 带 `skywalking.` 前缀的 system property **不存在这个键**，写了也不报错、只是静默不生效。
    # 这个错法最阴的地方是：参数看起来在、日志里也能 grep 到，而图谱照样一条边都进不去。
    if [ "$svc" = "knowledge-service" ]; then
      extra+=(SW_EXCLUDE_PLUGINS=neo4j-4.x)
    fi
    agent_args+=(-Dspring-boot.run.jvmArguments="$sw_args")
  else
    echo "  （未找到 SkyWalking agent，$svc 将不带链路追踪启动）" >&2
  fi

  mkdir -p "$LOG_DIR"
  rotate_log "$svc"
  echo "启动 $svc (端口 $(port_of "$svc")) → $LOG_DIR/$svc.log"
  # 单服务启动时自己重编一次（U71 防呆）：VSCode 的 Java 扩展（ECJ）编译失败时照样往
  # target/classes 写桩 class，而 Maven 增量编译按时间戳判断"class 比源码新"于是跳过重编，
  # 直接跑那个坏 class——三种面孔都见过：接口 500、启动即 NoClassDefFoundError、
  # 以及最阴的"代码改了但行为没变"。clean 会删掉 ECJ 写的桩。
  #
  # 但批量启动时不在这里做：一次 clean compile 实测 12.5 秒，九个串行近两分钟，
  # 而它防的是"单服务被单独重启时跑旧 class"这一种情形。批量的等价保证由
  # precompile_all() 一次全量完成（同样 clean，覆盖全部模块）。
  if [ "${PRECOMPILED:-0}" != "1" ]; then
    mvn -q -B -pl "$svc" clean compile >> "$LOG_DIR/$svc.log" 2>&1 || {
      echo "  ⚠ $svc 编译失败，跳过启动（详见 $LOG_DIR/$svc.log）" >&2
      return 1
    }
  fi
  env "${extra[@]}" nohup mvn -q -pl "$svc" spring-boot:run "${agent_args[@]}" > "$LOG_DIR/$svc.log" 2>&1 &
  # disown 不是可选项：只写 nohup ... & 时，MSYS/Git Bash 会在脚本退出时
  # 回收整个作业组，服务随之被杀——而日志里只留下 Spring 正常关闭的样子，
  # 看起来像服务自己崩了。加上 disown，进程才真正不属于这个 shell。
  #
  # 这个坑的判据很明确：**手工在交互 shell 里跑同一个脚本，服务活得好好的；
  # 由子进程（验收脚本 spawnSync）调用时它就活不过启动脚本的退出**。
  # 差别不在命令，在父 shell 何时消失。
  disown 2>/dev/null || true
}

# 服务日志按次归档。
#
# 启动用 `>` 覆盖同名日志——于是"上一轮到底发生了什么"往往在下一次启动时被抹掉，
# 而它恰恰是排查事故时要的第一份证据：2026-10-02 的死信事故里，product-service
# 出现异常的那一轮日志就是被一次 restart 覆盖的，最后只能靠"与全部证据相容的
# 唯一解释"来定性。归档后每一次启动都留下完整的上一轮现场。
# 同服务只保留最近 5 份，避免长期演示环境无限膨胀；要更久的历史就在启动前手动拷走。
rotate_log() { # 服务名
  local log="$LOG_DIR/$1.log" stamp
  [ -s "$log" ] || return 0
  mkdir -p "$LOG_DIR/archive"
  stamp=$(date +%Y%m%d-%H%M%S)
  mv "$log" "$LOG_DIR/archive/$1-$stamp.log"
  ls -1t "$LOG_DIR/archive/$1-"*.log 2>/dev/null | tail -n +6 | while read -r old; do
    rm -f "$old"
  done
}

# 按端口杀监听进程（服务与前端共用这一条路径）。
kill_port() { # 名称 端口
  local pid
  pid=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$2 " | awk '{print $NF}' | head -1)
  if [ -n "${pid:-}" ]; then
    echo "停止 $1 (端口 $2, PID $pid)"
    powershell -Command "Stop-Process -Id $pid -Force" 2>/dev/null || kill "$pid" 2>/dev/null
  else
    echo "跳过 $1 (端口 $2 上没有监听进程)"
  fi
}

# 「整场停」的快速版，给窗口绑定用：**要快**。
# 关窗时 mintty 发完 SIGHUP 很快就会回收进程，照 kill_port 逐个走（每个起一次
# powershell，0.2 秒起）会被半路截断；这里 netstat 一次收齐全部监听端口、
# 一条 taskkill 全杀（含前端 5173），几百毫秒内完成。停漏的兜底是 ./run-local.sh stop。
stop_all_fast() {
  local ports pattern pids args=() p
  ports="5173 $(for s in "${SERVICES[@]}"; do port_of "$s"; done)"
  pattern=":($(echo $ports | tr ' ' '|')) "
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep -E "$pattern" | awk '{print $NF}' | sort -u)
  if command -v taskkill >/dev/null 2>&1; then
    for p in $pids; do args+=(//PID "$p"); done
    [ ${#args[@]} -gt 0 ] && taskkill //F "${args[@]}" >/dev/null 2>&1
  else
    for p in $pids; do kill "$p" 2>/dev/null; done   # 非 Windows 兜底
  fi
  return 0
}

# 双击场景的窗口绑定：关窗（SIGHUP）或 Ctrl-C（SIGINT）时把服务与前端一并停掉——
# 服务是 nohup 起的（脱离本 bash、免疫 SIGHUP），不显式停就会留一堆孤儿进程。
# 非交互场景不注册：终端里 Ctrl-C 应该只是打断命令，不该顺手杀掉在跑的服务。
[ -n "$INTERACTIVE" ] && trap 'echo; echo "收到退出信号——停止全部服务与前端..."; stop_all_fast; echo "已停止（中间件容器保持运行）"; exit 0' INT TERM HUP

# 停服务。不传名字就停全部，传了就只停传的那些。
# <p>
# 早先这个函数无条件遍历全部服务、把参数丢掉——`stop knowledge-service` 会静默地
# 停掉八个服务。想只重启一个服务的人，得到的是一整套服务消失。
stop_services() {
  local svc
  local targets=("$@")
  [ ${#targets[@]} -eq 0 ] && targets=("${SERVICES[@]}")

  for svc in "${targets[@]}"; do
    index_of "$svc" >/dev/null || { echo "未知服务: $svc" >&2; continue; }
    kill_port "$svc" "$(port_of "$svc")"
  done
}

# 「已就绪」的判据只有一份：端口在监听 + actuator 应答。网关没有 actuator 依赖，
# /actuator/health 会走它的路由落到下游并返回 404——那恰恰说明它已经在转发了。
# wait_healthy 与补启轮的复查共用这个函数；两处各写一份的话，迟早分叉成
# 「脚本说全就绪、实际有个服务半死」或反向的假红灯。
#
# 三态返回，因为「端口在监听、但端点说还没好」是**正常的启动中间态**，不是失败：
#   0 就绪（200，或网关那种 404）
#   2 启动中（503——Spring 的 readiness 还是 OUT_OF_SERVICE，典型的是应用在跑
#     ApplicationReadyEvent 里的启动任务。ai-service 建全量检索索引要几分钟，
#     这几分钟里它每一条日志都正常、进程也健康，只是还不能接流量）
#   1 未响应/未监听（进程没起来、崩了，或端口不通）
#
# 2026-10-06 踩到的坑：把 503 和「没起来」揉成同一种失败，补启轮就会反复 kill 掉
# 一个正在正常建索引的 ai-service，索引从头再建一遍，永远追不上等待窗口——
# 表现是「ai-service 补启两轮仍未就绪」，而它其实每次都跑得好好的。
svc_ready() { # 服务名 → 0 就绪 / 2 启动中 / 1 未响应
  local port code
  port=$(port_of "$1")
  port_listening "$port" || return 1
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:$port/actuator/health" 2>/dev/null)
  case "$code" in
    200|404) return 0 ;;
    503) return 2 ;;
    *) return 1 ;;
  esac
}

# 复查判定加一次复验（间隔 2 秒）：单发失败可能只是瞬时抖动（curl 2 秒超时、
# 机器正忙），而它触发的动作是 kill + 重启一个**本来健康**的服务——2026-10-03
# 冷启动实测里 auth-service 就这样被误杀过一次（被杀前日志毫无异常）。
# 两次都失败才判失败。
#
# 返回 0 就绪、2 启动中（活着，**不能杀**）、1 失败（可以补启）。
svc_ready_confirm() { # 服务名 → 0 就绪 / 2 启动中 / 1 失败
  local rc
  svc_ready "$1"; rc=$?
  [ "$rc" -eq 0 ] && return 0
  sleep 2
  svc_ready "$1"
}

# 等服务就绪：并发轮询全部目标，谁好了标记谁，不互相拖累。
#
# 三种状态：就绪 / 启动中 / **启动已失败**（端口没监听，且日志里已出现进程死亡的
# 标志——Spring 的 "Application run failed"，或 Maven 的 BUILD FAILURE /
# "Process terminated with exit code"）。死进程等多久都不会活，判死即可，补启轮会
# 重拉它。2026-10-02 实测九服务冷启动：旧的串行等待在三个死进程上白等 9 分钟，
# 而失败结论在日志里早就写好了——等待修不了死进程，只能更快识别。
#
# 判死查**整个日志**，不能只看尾部窗口：死讯后面还跟着几十 KB 的异常堆栈和 Maven
# 的 [ERROR] 块——实测 `tail -c 4000` 漏掉了它（marker 距末尾 27 KB），于是死进程
# 被当成"启动中"白等了整整 180 秒。
wait_healthy() { # [服务名...]，不传则检查全部；未全就绪返回 1
  local svc
  local targets=("$@")
  [ ${#targets[@]} -eq 0 ] && targets=("${SERVICES[@]}")

  declare -A state=()   # 0=启动中 1=就绪 2=已失败
  for svc in "${targets[@]}"; do state[$svc]=0; done

  local pending
  # 用墙钟截止而不是轮数预算：每轮要对每个 pending 服务串行做 netstat + curl，
  # 未监听的端口上 curl 要耗满 --max-time，一轮最坏能到十几秒——「90 轮」这种写法
  # 名义 180 秒、实际窗口随负载剧烈浮动，实测最晚起的 ai-service 就卡在预算边缘被
  # 误报成「未就绪」。窗口必须与每轮耗时解耦。
  local deadline=$(( $(date +%s) + WAIT_HEALTHY_SECONDS ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    pending=()
    for svc in "${targets[@]}"; do
      [ "${state[$svc]}" -ne 0 ] && continue
      svc_ready "$svc"; local rc=$?
      case "$rc" in
        0) state[$svc]=1; printf '%-18s就绪\n' "$svc" ;;
        2) pending+=("$svc") ;;   # 端口通、readiness 还是 OUT_OF_SERVICE：正在初始化，继续等
        *)
          if grep -qE "Application run failed|BUILD FAILURE|Process terminated with exit code" "$LOG_DIR/$svc.log" 2>/dev/null; then
            state[$svc]=2; printf '%-18s启动失败（看 %s/%s.log）\n' "$svc" "$LOG_DIR" "$svc"
          else
            pending+=("$svc")
          fi
          ;;
      esac
    done
    [ ${#pending[@]} -eq 0 ] && break
    sleep 2
  done

  local ok=1
  for svc in "${targets[@]}"; do
    case "${state[$svc]}" in
      1) ;;
      0) printf '%-18s未就绪（%s 秒内没等到，看 %s/%s.log）\n' "$svc" "$WAIT_HEALTHY_SECONDS" "$LOG_DIR" "$svc"; ok=0 ;;
      *) ok=0 ;;
    esac
  done
  [ "$ok" -eq 1 ]
}

# ———————————————————— 演示环境一键启动 ————————————————————
#
# 面向「二面要现场演示」：一条命令把中间件、九个服务、前端全部拉起到就绪，
# 最后打印演示入口。幂等——已经在跑的部分全部跳过，重复执行不会起两份。

port_listening() {
  netstat -ano 2>/dev/null | grep LISTENING | grep -q ":$1 "
}

# 真连一次再下结论。踩过的坑：Docker Desktop 卡死时中间件端口仍显示 LISTENING，
# 连上去却永远没有握手响应——只看 netstat 会把死掉的环境判成「就绪」，
# 然后九个服务排着队超时，报出来的错和真正的问题隔着十万八千里。
tcp_open() {
  node -e "const s=require('net').connect($1,'127.0.0.1');const t=setTimeout(()=>process.exit(1),2000);s.on('connect',()=>{clearTimeout(t);process.exit(0)});s.on('error',()=>process.exit(1))" 2>/dev/null
}

http_ok() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1")" = "200" ]
}

wait_ready() { # 名称 超时秒 检查命令...
  local name=$1 timeout=$2
  shift 2
  printf '%-14s' "$name"
  local i
  for i in $(seq 1 "$timeout"); do
    if "$@" >/dev/null 2>&1; then
      echo "就绪"
      return 0
    fi
    sleep 1
  done
  echo "未就绪"
  return 1
}

# 派生数据重建：product_spu 上的评分与销量是从评价、订单**算出来**的冗余，
# 换过库、或者消息丢过之后它就会与权威值对不上——而页面上看不出哪边是对的，
# 只会看到「商品卡写 5.0 分 0 条评价、详情页写 4.2 分 9 条」。
# 重建走 MQ 是异步的，所以等到**看得见收敛**再放行，否则演示首页会先给出一屏错分数。
resync_derived() {
  printf '%-18s' "派生数据重建"
  local t0=$SECONDS   # 它是「服务全部就绪」到「演示就绪」之间的主要等待，耗时要量得出来

  local recomputed
  recomputed=$(curl -s -X POST --max-time 10 \
    http://127.0.0.1:9006/reviews/internal/aggregates/republish 2>/dev/null)
  if [ -z "$recomputed" ]; then
    # 不挡住演示：错的是评分这一项派生数据，不是整个环境
    echo "跳过（review-service 没响应，看 $LOG_DIR/review-service.log）"
    return 0
  fi

  local i
  for i in $(seq 1 30); do
    if derived_converged; then
      echo "就绪（耗时 $((SECONDS - t0)) 秒）"
      return 0
    fi
    sleep 1
  done
  echo "未收敛（等了 $((SECONDS - t0)) 秒，评分可能滞后，看 $LOG_DIR/product-service.log）"
  return 0
}

# 拿「商品侧声称有评价的某个商品」，去评价服务问权威值，对得上才算收敛。
# 不写死商品 id：换了语料/种子之后写死的那个可能一条评价都没有，
# 那样这条检查会永远等不到而变得看不出真假。
derived_converged() {
  node -e '
    const get = async (url) => (await fetch(url, { signal: AbortSignal.timeout(3000) })).json();
    (async () => {
      const catalog = ((await get("http://127.0.0.1:9002/products/internal/catalog")).data) ?? [];
      const product = catalog.find((p) => (p.reviewCount ?? 0) > 0);
      if (!product) process.exit(1);
      const stats = (await get(`http://127.0.0.1:9006/reviews/spu/${product.id}/statistics`)).data;
      const same = stats.total === product.reviewCount
        && Number(stats.average) === Number(product.ratingAvg);
      process.exit(same ? 0 : 1);
    })().catch(() => process.exit(1));
  ' >/dev/null 2>&1
}

preflight() {
  if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    echo "Docker 引擎不可用——中间件全部跑在容器里，先确认 Docker Desktop 在运行（卡死的话重启它）" >&2
    return 1
  fi
  command -v node >/dev/null 2>&1 || { echo "缺 node（中间件探测与前端都要用）" >&2; return 1; }
  command -v pnpm >/dev/null 2>&1 || { echo "缺 pnpm（前端要 pnpm dev）" >&2; return 1; }
  [ -f ../docker-compose.yml ] || { echo "找不到 ../docker-compose.yml" >&2; return 1; }
  return 0
}

# 中间件就绪探测：一个组件一条真握手，与 middleware_up 的检查清单逐项对应。
probe_middleware() { # 组件名 → 就绪返回 0
  case "$1" in
    nacos)         http_ok http://127.0.0.1:8848/nacos/v1/console/health/readiness ;;
    mysql)         tcp_open 3306 ;;
    redis)         tcp_open 6379 ;;
    rabbitmq)      tcp_open 5672 ;;
    elasticsearch) http_ok http://127.0.0.1:9200/ ;;
    milvus)        http_ok http://127.0.0.1:9091/healthz ;;
    neo4j)         tcp_open 7687 ;;
    seata)         tcp_open 8091 ;;
    *)             return 1 ;;
  esac
}

middleware_up() {
  echo "启动中间件（docker compose up -d，已在跑的容器不受影响）..."
  # compose 的退出码只报「哪些容器没起来」，不是「环境不可用」——所以就绪与否
  # 一律以下面的真握手为准。MySQL 跑在宿主机（Windows 服务、开机自启），不在
  # compose 里，下面按 3306 端口检查它。
  docker compose -f ../docker-compose.yml up -d \
    || echo "（docker compose 报了错，继续——中间件就绪与否看下面的真握手检查）"

  # 就绪检查：并行轮询 + 240 秒总预算。
  #
  # 为什么不用 wait_ready 串行等：8 项各自计时、超时叠加，冷启动（Docker Desktop
  # 刚起来、十来个容器一起拉）时最坏会拖几十分钟才报出「全部未就绪」；并行轮询的
  # 总耗时是最慢那一项的就绪时间，与 wait_healthy 同一模式。
  #
  # 为什么超时从 60/90 秒提到 240：给「开机后 Docker 冷启动中立刻跑 demo」留预算——
  # Milvus 要等 etcd/minio 先起、ES 冷启动也慢，90 秒会把「还在启动」误报成
  # 「未就绪」。探测就绪即返回，正常路径不为这个数字多等。
  local name still
  local pending=(nacos mysql redis rabbitmq elasticsearch milvus neo4j seata)
  local deadline=$(( $(date +%s) + 240 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    still=()
    for name in "${pending[@]}"; do
      if probe_middleware "$name"; then
        printf '%-14s就绪\n' "$name"
      else
        still+=("$name")
      fi
    done
    pending=("${still[@]}")
    [ ${#pending[@]} -eq 0 ] && break
    sleep 2
  done

  if [ ${#pending[@]} -ne 0 ]; then
    echo "有中间件没就绪：${pending[*]}。服务连不上 Nacos/MySQL 会起不来或静默降级，先解决上面未就绪的：" >&2
    echo "  docker ps · docker compose -f docker-compose.yml logs <容器名> · 卡死就重启 Docker Desktop" >&2
    return 1
  fi
  return 0
}

# 构建前端生产产物（产物进 ../frontend/dist，preview 从这里服务）。
frontend_build() {
  mkdir -p "$LOG_DIR"
  rotate_log frontend-build
  pnpm -C ../frontend build > "$LOG_DIR/frontend-build.log" 2>&1
}

# demo_up 在启动流程最前面调用它，把构建放进后台与启动并行：构建只编译静态资源，
# 与中间件/服务零依赖，几秒的构建被后面几十秒以上的启动流程完全盖住。
# （原先它串在 resync 之后——服务全就绪了还要先等收敛、再花几秒构建，
# 观感就是「后端都好了前端还在磨蹭」。）
# 前端已在跑（5173 监听）时跳过：frontend_up 也会跳过，重建 dist 反而会把
# 正在服务的预览页搅出"新 index + 旧 chunk"的混杂状态。
frontend_build_bg() {
  port_listening 5173 && return 0
  frontend_build &
  FE_BUILD_PID=$!
}

# mode: dev | master（master = 生产产物；旧名 prod 一并对齐，避免两处叫法分叉）
frontend_up() {
  local mode="${1:-dev}"
  [ "$mode" = "prod" ] && mode=master
  if port_listening 5173; then
    echo "跳过前端（5173 已在监听）"
    return 0
  fi
  if [ ! -d ../frontend/node_modules ]; then
    echo "前端依赖未安装：先执行 cd frontend && pnpm install" >&2
    return 1
  fi
  mkdir -p "$LOG_DIR"
  if [ "$mode" = "master" ]; then
    # 演示走生产产物而不是 dev server：首屏不用现编译，比 dev 快，也不带 HMR 的抖动。
    # （Vue DevTools 的悬浮面板已被 vite.config.ts 的 appendTo 关掉，不再是理由，
    #  生产产物本来就干净——这条注释按事实更正，避免下一个人以为它还飘着。）
    # 前端 API 地址是写死的 http://localhost:8080（本项目不用 vite proxy），preview 无需额外配置
    if [ -n "${FE_BUILD_PID:-}" ]; then
      # 构建已在 demo_up 开头后台发起，这里只等它收尾（通常早已完成）
      printf '%-16s' "前端构建"
      if wait "$FE_BUILD_PID"; then
        echo "完成（与后端启动并行）"
      else
        echo "失败（看 $LOG_DIR/frontend-build.log）" >&2
        return 1
      fi
      FE_BUILD_PID=""
    else
      echo "构建前端生产产物..."
      frontend_build || { echo "前端构建失败，看 $LOG_DIR/frontend-build.log" >&2; return 1; }
    fi
    echo "启动前端 (端口 5173, 生产产物) → $LOG_DIR/frontend.log"
    rotate_log frontend
    nohup pnpm -C ../frontend preview --port 5173 --strictPort > "$LOG_DIR/frontend.log" 2>&1 &
    disown 2>/dev/null || true
  else
    echo "启动前端 (端口 5173) → $LOG_DIR/frontend.log"
    rotate_log frontend
    nohup pnpm -C ../frontend dev > "$LOG_DIR/frontend.log" 2>&1 &
    disown 2>/dev/null || true
  fi
  if wait_ready frontend 60 http_ok http://127.0.0.1:5173/; then
    return 0
  fi
  echo "前端没起来，看 $LOG_DIR/frontend.log" >&2
  return 1
}

print_entries() { # mode
  local mode="$1"
  local title hint
  if [ "$mode" = "dev" ]; then
    title="开发环境就绪（前端 dev server，支持热更新）"
    hint="改前端源码即时生效；改后端仍需 ./run-local.sh stop <服务> 后重启"
  else
    title="演示环境就绪（前端生产产物，无热更新）"
    hint="画面干净、首屏快，适合演示/投屏/验收"
  fi
  cat <<EOF

==================== $title ====================
前端首页            http://localhost:5173/#/shop
AI 助手（Agent）    http://localhost:5173/#/assistant
知识库              http://localhost:5173/#/knowledge
成分与相互作用图谱  http://localhost:5173/#/knowledge/graph
检索质量评测        http://localhost:5173/#/knowledge/eval
回答质量评测        http://localhost:5173/#/knowledge/eval/answer
管理台              http://localhost:5173/#/admin
API 网关            http://localhost:8080
链路追踪（可选）    http://localhost:8088
账号                admin / 123456（管理员）· alice / 123456（普通用户）
----------------------------------------------------
$hint
=====================================================
EOF
}

# 双击场景的收尾（非交互直接返回，命令行交还给终端）：
# 启动完成后脚本**驻留不退出**，窗口 = 演示环境总开关——关窗 / Ctrl-C 由上面的
# trap 停掉全部服务与前端。失败时同样驻留，让报错留在屏幕上看得到。
hold_window() {
  [ -n "$INTERACTIVE" ] || return 0
  cat <<'EOF'

==================== 服务运行中 ====================
本窗口只管前后端（9 个服务 + 前端页面），中间件容器归 Docker Desktop 管。
  关闭本窗口 或 Ctrl-C   停止全部服务与前端
若窗口被强制结束、有残留，兜底： ./run-local.sh stop
==================================================
EOF
  while sleep 60; do :; done
}

# 起全部服务：错峰启动 → 等就绪 → 未就绪的自动补启（最多两轮）。
#
# 为什么必须错峰：九个 JVM 同时向 Nacos 建 gRPC 长连接（AI 服务还要连 Milvus），
# 建连窗口挤在 CPU 最饱和的启动期，注册请求撞上客户端连接还没就绪的窗口
# （failFast=true 直接退出）。2026-10-02 实测：错峰 3 秒首轮崩三个（Nacos 注册
# STARTING ×2、Milvus 连接超时 ×1）；错峰 8 秒把 Nacos 类失败压到每轮 0~1 例
# （概率性，未根除），Milvus 建连超时由 ai-service 侧显式延长建连预算解决
# （见 AiAgentConfig.newMilvusStore）。
#
# 补启轮是最终承诺：2026-10-02 三轮冷启动实测里首轮失败的服务被它全部救回（累计 5/5），
# 而且补启时 CPU 已空闲、Nacos 连接无竞争——重试的成功率本来就比首轮高。面试现场
# 能接受自动重试的插曲，不能接受有服务起不来。
start_all_services() {
  local svc port round
  local failed=()
  precompile_all
  export PRECOMPILED=1
  for svc in "${SERVICES[@]}"; do
    port=$(port_of "$svc")
    if port_listening "$port"; then
      echo "跳过 $svc（端口 $port 已在监听）"
    else
      start_one "$svc"
      # 错峰从 8 秒收到 2 秒。原先那 8 秒是为了绕开 Nacos 注册竞态，而竞态的真正修法是
      # 配置层的容错参数（已提为九服务共享的 envoymart-nacos.yml，见 common 模块）——
      # 靠拉长错峰只是降低概率，治不了根。竞态修掉后，这里的错峰只剩"别让九个 JVM
      # 在同一毫秒抢 CPU"的作用，2 秒足够，九个服务因此少等 54 秒。
      # 真实失败仍由补启轮兜底，"最终全部就绪"这个承诺不变。
      sleep 2
    fi
  done
  wait_healthy

  for round in 1 2; do
    failed=()
    starting=()
    for svc in "${SERVICES[@]}"; do
      svc_ready_confirm "$svc"; rc=$?
      case "$rc" in
        0) ;;
        # 仍在初始化（readiness 说 OUT_OF_SERVICE）：**绝不能补启**。
        # 它的进程是好的，杀它等于把已经跑了半程的启动任务清零重来——
        # ai-service 建全量索引要几分钟，被这样反复杀掉就永远到不了就绪。
        2) starting+=("$svc") ;;
        *) failed+=("$svc") ;;
      esac
    done
    [ ${#failed[@]} -eq 0 ] && break
    echo "第 $round 轮补启：${failed[*]}"
    for svc in "${failed[@]}"; do
      # 先清端口再起。failFast 退出的进程没留下监听，但「活着只是没就绪」的也有
      # （等满 180 秒仍不响应）——那类不清掉，新进程绑不上端口。
      kill_port "$svc" "$(port_of "$svc")"
      start_one "$svc"
      sleep 2
    done
    wait_healthy "${failed[@]}"
  done

  # 终判也分三态：仍在初始化的服务**不算失败**，它的启动任务还在跑，
  # 该做的只是把这件事说清楚，而不是回一个「环境没起来」。
  failed=(); starting=()
  for svc in "${SERVICES[@]}"; do
    svc_ready "$svc"; rc=$?
    case "$rc" in
      0) ;;
      2) starting+=("$svc") ;;
      *) failed+=("$svc") ;;
    esac
  done
  if [ ${#starting[@]} -gt 0 ]; then
    echo "仍在初始化（端口已通，暂不能接流量）：${starting[*]}——启动任务在后台继续跑，不必重启"
  fi
  if [ ${#failed[@]} -gt 0 ]; then
    echo "补启两轮后仍未就绪：${failed[*]}（看 $LOG_DIR/<服务>.log 末尾的报错）" >&2
    return 1
  fi
  return 0
}

# 一键启动的公共主体。前端那一段按 mode 分叉，其余（预检 / 中间件 / 九服务 / 派生数据）完全相同：
# 两个模式共用一套启动流程，是为了让「dev 能跑起来的东西 master 也一定能跑起来」——
# 两套流程各写一份的话，迟早出现「演示能过、开发跑不起来」这类只在一边复现的问题。
#   mode=dev    前端走 pnpm dev（HMR）
#   mode=master 前端走生产产物（vite build + preview）
run_all() { # mode
  local mode="$1"
  # 分段计时：启动慢的时候，「慢在预检/中间件/服务/前端」必须一眼看得出来，
  # 而不是靠人肉盯日志猜。整段结束打印一行阶段汇总。
  local t_begin=$SECONDS t_pre=$SECONDS t_mid t_svc t_derived t_fe

  preflight || { echo "预检未过，环境没启动" >&2; exit 1; }
  t_pre=$((SECONDS - t_pre))

  # 只有生产产物才需要提前构建；dev 模式现编译，无需这一步。
  # 构建与中间件/服务零依赖，放进后台与启动并行（见 frontend_build_bg）。
  [ "$mode" = "master" ] && frontend_build_bg

  t_mid=$SECONDS
  middleware_up || exit 1
  t_mid=$((SECONDS - t_mid))

  t_svc=$SECONDS
  start_all_services || exit 1
  t_svc=$((SECONDS - t_svc))
  t_derived=$SECONDS
  resync_derived
  t_derived=$((SECONDS - t_derived))

  t_fe=$SECONDS
  frontend_up "$mode" || exit 1
  t_fe=$((SECONDS - t_fe))

  printf '启动阶段耗时：预检 %s 秒 · 中间件 %s 秒 · 服务 %s 秒 · 派生数据 %s 秒 · 前端 %s 秒 · 合计 %s 秒\n' \
    "$t_pre" "$t_mid" "$t_svc" "$t_derived" "$t_fe" "$((SECONDS - t_begin))"
  print_entries "$mode"
}

# 一键启动两个模式走同一段收尾：全程输出各落一份日志（双击场景窗口会关，事后靠它复盘）。
one_shot() { # 子命令名 前端模式 中文名
  local cmd="$1" mode="$2" label="$3"
  mkdir -p "$LOG_DIR"
  rotate_log "$cmd"
  if run_all "$mode" 2>&1 | tee "$LOG_DIR/$cmd.log"; then
    echo "✓ $label 全部就绪（完整输出已存 $LOG_DIR/$cmd.log）"
    rc=0
  else
    echo "✗ $label 没起来——往上翻找报错，完整输出已存 $LOG_DIR/$cmd.log" >&2
    rc=1
  fi
  hold_window
  exit "$rc"
}

case "${1:-all}" in
  stop) shift
    if [ $# -eq 0 ]; then
      kill_port frontend 5173
    fi
    stop_services "$@"
    echo "（中间件容器保持运行；要一并停用 docker compose -f docker-compose.yml stop）"
    ;;
  dev)     one_shot dev    dev    开发环境 ;;
  master)  one_shot master master 演示环境 ;;
  # demo 是 master 的旧名。保留别名是因为 README / 规划文档 / 验收脚本注释里都还写着它，
  # 改名会让那些地方的说明一夜失效——下次同步文档时再逐步替换，行为完全一致。
  demo)    one_shot master master 演示环境 ;;
  all)  start_all_services || exit 1 ;;
  *)    for svc in "$@"; do start_one "$svc"; done ;;
esac

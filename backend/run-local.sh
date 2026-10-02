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
#   ./run-local.sh demo              演示环境一键启动：中间件 + 全部服务 + 前端 + 演示入口
#   ./run-local.sh stop              停止全部（含前端；中间件容器保持运行）
#   ./run-local.sh stop ai-service   只停止指定的一个或多个
#   ./run-local.sh auth-service      只启动指定的一个或多个
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
  # 关掉 SkyWalking 的 Neo4j 插件（插件名取自它自己的 skywalking-plugin.def）。
  #
  # 它是给 neo4j-java-driver 4.x 写的，本项目用 5.26.0。插桩之后，凡是<b>真的写进去关系</b>
  # 的事务都会抛 "RuntimeException: Can not do async finish for the span repeatedly."，
  # 而同一个 Cypher 在 cypher-shell 里手工跑完全正常——所以极易误判成 Cypher 语法或连接超时。
  # 症状是知识图谱一条边都进不去，且只发生在「有边可写」的文档上：
  # 只跑一条 DELETE 的事务反而成功，看起来像随机失败。
  #
  # 代价：Neo4j 调用不再作为独立出口 span 出现在链路里，服务级与 HTTP 链路追踪不受影响。
  # 要拿回 Neo4j span 的办法是升级插件，不是打开它——打开就是上面那条报错。
  local agent_args=()
  if [ -n "$AGENT_JAR" ]; then
    local sw_args="-javaagent:$AGENT_JAR -Dskywalking.agent.service_name=$svc -Dskywalking.collector.backend_service=$SW_OAP"
    [ "$svc" = "knowledge-service" ] && sw_args="$sw_args -Dskywalking.plugin.exclude_plugins=neo4j-4.x"
    agent_args+=(-Dspring-boot.run.jvmArguments="$sw_args")
  else
    echo "  （未找到 SkyWalking agent，$svc 将不带链路追踪启动）" >&2
  fi

  mkdir -p "$LOG_DIR"
  rotate_log "$svc"
  echo "启动 $svc (端口 $(port_of "$svc")) → $LOG_DIR/$svc.log"
  env "${extra[@]}" nohup mvn -q -pl "$svc" spring-boot:run "${agent_args[@]}" > "$LOG_DIR/$svc.log" 2>&1 &
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
svc_ready() { # 服务名
  local port code
  port=$(port_of "$1")
  port_listening "$port" || return 1
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health" 2>/dev/null)
  case "$code" in
    200|404) return 0 ;;
    *) return 1 ;;
  esac
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
  for _ in $(seq 1 90); do   # 90 轮 × 2 秒 = 180 秒总余量（实测最慢服务带 agent 启动 66 秒）
    pending=()
    for svc in "${targets[@]}"; do
      [ "${state[$svc]}" -ne 0 ] && continue
      if svc_ready "$svc"; then
        state[$svc]=1; printf '%-18s就绪\n' "$svc"
      elif grep -qE "Application run failed|BUILD FAILURE|Process terminated with exit code" "$LOG_DIR/$svc.log" 2>/dev/null; then
        state[$svc]=2; printf '%-18s启动失败（看 %s/%s.log）\n' "$svc" "$LOG_DIR" "$svc"
      else
        pending+=("$svc")
      fi
    done
    [ ${#pending[@]} -eq 0 ] && break
    sleep 2
  done

  local ok=1
  for svc in "${targets[@]}"; do
    case "${state[$svc]}" in
      1) ;;
      0) printf '%-18s未就绪（180 秒内没等到，看 %s/%s.log）\n' "$svc" "$LOG_DIR" "$svc"; ok=0 ;;
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
      echo "就绪"
      return 0
    fi
    sleep 1
  done
  echo "未收敛（评分可能滞后，看 $LOG_DIR/product-service.log）"
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

frontend_up() {
  local mode="${1:-dev}"
  if port_listening 5173; then
    echo "跳过前端（5173 已在监听）"
    return 0
  fi
  if [ ! -d ../frontend/node_modules ]; then
    echo "前端依赖未安装：先执行 cd frontend && pnpm install" >&2
    return 1
  fi
  mkdir -p "$LOG_DIR"
  if [ "$mode" = "prod" ]; then
    # 演示走生产产物而不是 dev server：dev 模式会注入 Vue DevTools 悬浮面板
    # （插件没有隐藏开关），投屏演示时它一直飘在页面角落；首屏还要现编译，比静态产物慢。
    # 前端 API 地址是写死的 http://localhost:8080（本项目不用 vite proxy），preview 无需额外配置
    echo "构建前端生产产物（约 20 秒）..."
    rotate_log frontend-build
    if ! pnpm -C ../frontend build > "$LOG_DIR/frontend-build.log" 2>&1; then
      echo "前端构建失败，看 $LOG_DIR/frontend-build.log" >&2
      return 1
    fi
    echo "启动前端 (端口 5173, 生产产物) → $LOG_DIR/frontend.log"
    rotate_log frontend
    nohup pnpm -C ../frontend preview --port 5173 --strictPort > "$LOG_DIR/frontend.log" 2>&1 &
  else
    echo "启动前端 (端口 5173) → $LOG_DIR/frontend.log"
    rotate_log frontend
    nohup pnpm -C ../frontend dev > "$LOG_DIR/frontend.log" 2>&1 &
  fi
  if wait_ready frontend 60 http_ok http://127.0.0.1:5173/; then
    return 0
  fi
  echo "前端没起来，看 $LOG_DIR/frontend.log" >&2
  return 1
}

print_entries() {
  cat <<'EOF'

==================== 演示环境就绪 ====================
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
=====================================================
EOF
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
  for svc in "${SERVICES[@]}"; do
    port=$(port_of "$svc")
    if port_listening "$port"; then
      echo "跳过 $svc（端口 $port 已在监听）"
    else
      start_one "$svc"
      sleep 8
    fi
  done
  wait_healthy

  for round in 1 2; do
    failed=()
    for svc in "${SERVICES[@]}"; do
      svc_ready "$svc" || failed+=("$svc")
    done
    [ ${#failed[@]} -eq 0 ] && break
    echo "第 $round 轮补启：${failed[*]}"
    for svc in "${failed[@]}"; do
      # 先清端口再起。failFast 退出的进程没留下监听，但「活着只是没就绪」的也有
      # （等满 180 秒仍不响应）——那类不清掉，新进程绑不上端口。
      kill_port "$svc" "$(port_of "$svc")"
      start_one "$svc"
      sleep 8
    done
    wait_healthy "${failed[@]}"
  done

  failed=()
  for svc in "${SERVICES[@]}"; do
    svc_ready "$svc" || failed+=("$svc")
  done
  if [ ${#failed[@]} -gt 0 ]; then
    echo "补启两轮后仍未就绪：${failed[*]}（看 $LOG_DIR/<服务>.log 末尾的报错）" >&2
    return 1
  fi
  return 0
}

demo_up() {
  preflight || { echo "预检未过，演示环境没启动" >&2; exit 1; }
  middleware_up || exit 1

  start_all_services || exit 1
  resync_derived

  frontend_up prod || exit 1
  print_entries
}

case "${1:-all}" in
  stop) shift
    if [ $# -eq 0 ]; then
      kill_port frontend 5173
    fi
    stop_services "$@"
    echo "（中间件容器保持运行；要一并停用 docker compose -f docker-compose.yml stop）"
    ;;
  demo) demo_up ;;
  all)  start_all_services || exit 1 ;;
  *)    for svc in "$@"; do start_one "$svc"; done ;;
esac

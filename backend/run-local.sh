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
#   ./run-local.sh stop              停止全部
#   ./run-local.sh stop ai-service   只停止指定的一个或多个
#   ./run-local.sh auth-service      只启动指定的一个或多个
#
set -uo pipefail
cd "$(dirname "$0")"

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
echo "同步库模块到本地仓库（改了 common / agent-core 后必须走这一步）..."
mvn -q -B install -DskipTests -pl common,agent-core || {
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
  echo "启动 $svc (端口 $(port_of "$svc")) → $LOG_DIR/$svc.log"
  env "${extra[@]}" nohup mvn -q -pl "$svc" spring-boot:run "${agent_args[@]}" > "$LOG_DIR/$svc.log" 2>&1 &
}

# 停服务。不传名字就停全部，传了就只停传的那些。
# <p>
# 早先这个函数无条件遍历全部服务、把参数丢掉——`stop knowledge-service` 会静默地
# 停掉八个服务。想只重启一个服务的人，得到的是一整套服务消失。
stop_services() {
  local svc port pid
  local targets=("$@")
  [ ${#targets[@]} -eq 0 ] && targets=("${SERVICES[@]}")

  for svc in "${targets[@]}"; do
    index_of "$svc" >/dev/null || { echo "未知服务: $svc" >&2; continue; }
    port=$(port_of "$svc")
    pid=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$port " | awk '{print $NF}' | head -1)
    if [ -n "${pid:-}" ]; then
      echo "停止 $svc (端口 $port, PID $pid)"
      powershell -Command "Stop-Process -Id $pid -Force" 2>/dev/null || kill "$pid" 2>/dev/null
    else
      echo "跳过 $svc (端口 $port 上没有监听进程)"
    fi
  done
}

wait_healthy() {
  local i port code ready
  for i in "${!SERVICES[@]}"; do
    printf '%-18s' "${SERVICES[$i]}"
    port=$(port_of "${SERVICES[$i]}")
    ready=""
    for _ in $(seq 1 90); do
      # 先看端口在不在监听；再看 actuator。网关没有 actuator 依赖，
      # /actuator/health 会走它的路由落到下游并返回 404——那恰恰说明它已经在转发了。
      if netstat -ano 2>/dev/null | grep LISTENING | grep -q ":$port "; then
        code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health" 2>/dev/null)
        case "$code" in
          200|404) ready=1; echo "就绪"; break ;;
        esac
      fi
      sleep 2
    done
    [ -n "$ready" ] || echo "未就绪（看 $LOG_DIR/${SERVICES[$i]}.log）"
  done
}

case "${1:-all}" in
  stop) shift; stop_services "$@" ;;
  all)  for svc in "${SERVICES[@]}"; do start_one "$svc"; done; wait_healthy ;;
  *)    for svc in "$@"; do start_one "$svc"; done ;;
esac

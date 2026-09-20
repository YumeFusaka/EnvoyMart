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
if [ -d "$AGENT_SRC" ]; then
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

export DB_DRIVER="${DB_DRIVER:-com.mysql.cj.jdbc.Driver}"
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-3306}"
export DB_USERNAME="${DB_USERNAME:-yumefusaka}"
export DB_PASSWORD="${DB_PASSWORD:-j}"
export NACOS_ENABLED="${NACOS_ENABLED:-true}"

# 启动顺序：网关先起（它会往 Nacos 注册），其余服务随后
SERVICES=(gateway-service auth-service product-service order-service ai-service payment-service review-service)
# 端口用于停止与健康检查；没有独立库的服务（网关、AI）留空
PORT_OF=(8080 9001 9002 9003 9004 9005 9006)
DB_OF=("" envoymart_auth envoymart_product envoymart_order "" envoymart_payment envoymart_review)

index_of() {
  local target=$1 i
  for i in "${!SERVICES[@]}"; do
    [ "${SERVICES[$i]}" = "$target" ] && { echo "$i"; return 0; }
  done
  return 1
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
    agent_args+=(-Dspring-boot.run.jvmArguments="-javaagent:$AGENT_JAR -Dskywalking.agent.service_name=$svc -Dskywalking.collector.backend_service=$SW_OAP")
  else
    echo "  （未找到 SkyWalking agent，$svc 将不带链路追踪启动）" >&2
  fi

  mkdir -p "$LOG_DIR"
  echo "启动 $svc (端口 ${PORT_OF[$idx]}) → $LOG_DIR/$svc.log"
  env "${extra[@]}" nohup mvn -q -pl "$svc" spring-boot:run "${agent_args[@]}" > "$LOG_DIR/$svc.log" 2>&1 &
}

stop_all() {
  local i pid
  for i in "${!SERVICES[@]}"; do
    pid=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":${PORT_OF[$i]} " | awk '{print $NF}' | head -1)
    if [ -n "${pid:-}" ]; then
      echo "停止 ${SERVICES[$i]} (端口 ${PORT_OF[$i]}, PID $pid)"
      powershell -Command "Stop-Process -Id $pid -Force" 2>/dev/null || kill "$pid" 2>/dev/null
    fi
  done
}

wait_healthy() {
  local i port code ready
  for i in "${!SERVICES[@]}"; do
    printf '%-18s' "${SERVICES[$i]}"
    port=${PORT_OF[$i]}
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
  stop) stop_all ;;
  all)  for svc in "${SERVICES[@]}"; do start_one "$svc"; done; wait_healthy ;;
  *)    for svc in "$@"; do start_one "$svc"; done ;;
esac

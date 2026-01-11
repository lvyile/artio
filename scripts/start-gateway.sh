#!/bin/bash

# BuySell Gateway 启动脚本
# 功能：编译并启动 BuySell Gateway 应用

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

echo "=== BuySell Gateway 启动脚本 ==="
echo ""

# 检查 Java 版本
if ! command -v java &> /dev/null; then
    echo "❌ 错误: 未找到 Java"
    echo "   请安装 Java 17 或更高版本"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^1\./s///' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ 错误: Java 版本过低 (需要 Java 17+)"
    echo "   当前版本: Java $JAVA_VERSION"
    exit 1
fi

echo "✅ Java 版本检查通过: $(java -version 2>&1 | head -1)"
echo ""

# 检查端口是否被占用
if lsof -Pi :9999 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    echo "⚠️  警告: 端口 9999 已被占用"
    echo "   请先停止占用该端口的进程，或修改 Gateway 配置使用其他端口"
    echo ""
    echo "   查看占用端口的进程:"
    lsof -Pi :9999 -sTCP:LISTEN
    echo ""
    read -p "是否继续？(y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 检查是否需要编译
GATEWAY_SOURCE="artio-samples/src/main/java/uk/co/real_logic/artio/gateway/BuySellGatewayApplication.java"
JAR_FILE="artio-samples/build/libs/samples.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "📦 编译项目并打包..."
    ./gradlew :artio-samples:shadowJar -x test
    echo ""
elif [ -f "$GATEWAY_SOURCE" ] && [ "$GATEWAY_SOURCE" -nt "$JAR_FILE" ]; then
    echo "📦 检测到源代码更新，重新编译并打包..."
    ./gradlew :artio-samples:shadowJar -x test
    echo ""
fi

# 设置系统属性（Aeron 通道配置）
export CONTROL_CHANNEL="aeron:udp?endpoint=localhost:10010"
export CONTROL_RESPONSE_CHANNEL="aeron:udp?endpoint=localhost:10020"
export REPLICATION_CHANNEL="aeron:udp?endpoint=localhost:0"

# 设置 JVM 参数
# 需要添加 --add-opens 以允许访问内部 API（Aeron 需要）
JVM_OPTS="-Xmx512m -Xms256m \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.base/java.util.zip=ALL-UNNAMED"

# 主类名
MAIN_CLASS="uk.co.real_logic.artio.gateway.BuySellGatewayApplication"

echo "🚀 启动 BuySell Gateway..."
echo "   监听端口: 9999"
echo "   TargetCompID: FGW"
echo "   允许的 SenderCompID: BUY, SELL"
echo ""
echo "   按 Ctrl+C 停止 Gateway"
echo ""

# 使用 JAR 文件运行（samples.jar 是包含所有依赖的 fat JAR）
# 注意：使用 -cp 和主类名，因为 samples.jar 可能没有设置 Main-Class
java $JVM_OPTS \
    -Dio.aeron.archive.client.AeronArchive.Configuration.CONTROL_CHANNEL_PROP_NAME="$CONTROL_CHANNEL" \
    -Dio.aeron.archive.client.AeronArchive.Configuration.CONTROL_RESPONSE_CHANNEL_PROP_NAME="$CONTROL_RESPONSE_CHANNEL" \
    -Dio.aeron.archive.Archive.Configuration.REPLICATION_CHANNEL_PROP_NAME="$REPLICATION_CHANNEL" \
    -cp "artio-samples/build/libs/samples.jar" \
    "$MAIN_CLASS" \
    "$@"

echo ""
echo "Gateway 已停止"

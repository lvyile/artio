#!/bin/bash

# 连接测试脚本
# 功能：启动 Gateway 和客户端，验证连接是否成功

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

echo "=== BuySell Gateway 连接测试 ==="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 清理函数
cleanup() {
    echo ""
    echo "🧹 清理资源..."
    
    # 停止 Gateway
    if [ ! -z "$GATEWAY_PID" ]; then
        echo "   停止 Gateway (PID: $GATEWAY_PID)..."
        kill $GATEWAY_PID 2>/dev/null || true
        wait $GATEWAY_PID 2>/dev/null || true
    fi
    
    # 停止 BUY 客户端
    if [ ! -z "$BUY_CLIENT_PID" ]; then
        echo "   停止 BUY 客户端 (PID: $BUY_CLIENT_PID)..."
        kill $BUY_CLIENT_PID 2>/dev/null || true
        wait $BUY_CLIENT_PID 2>/dev/null || true
    fi
    
    # 停止 SELL 客户端
    if [ ! -z "$SELL_CLIENT_PID" ]; then
        echo "   停止 SELL 客户端 (PID: $SELL_CLIENT_PID)..."
        kill $SELL_CLIENT_PID 2>/dev/null || true
        wait $SELL_CLIENT_PID 2>/dev/null || true
    fi
    
    echo "✅ 清理完成"
}

# 注册清理函数
trap cleanup EXIT INT TERM

# 检查端口是否被占用
check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1 ; then
        echo -e "${YELLOW}⚠️  警告: 端口 $port 已被占用${NC}"
        lsof -Pi :$port -sTCP:LISTEN
        echo ""
        read -p "是否继续？(y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

# 检查 Gateway 是否就绪
wait_for_gateway() {
    local max_attempts=30
    local attempt=0
    
    echo "⏳ 等待 Gateway 启动..."
    
    while [ $attempt -lt $max_attempts ]; do
        if lsof -Pi :9999 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
            echo -e "${GREEN}✅ Gateway 已就绪（端口 9999 正在监听）${NC}"
            sleep 2  # 再等待 2 秒确保完全启动
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    
    echo -e "${RED}❌ Gateway 启动超时${NC}"
    return 1
}

# 检查客户端是否连接成功
check_client_connection() {
    local client_name=$1
    local pid=$2
    local max_wait=10
    local wait_time=0
    
    echo "⏳ 检查 $client_name 连接状态..."
    
    # 等待进程启动
    sleep 2
    
    # 检查进程是否还在运行
    if ! kill -0 $pid 2>/dev/null; then
        echo -e "${RED}❌ $client_name 进程已退出${NC}"
        return 1
    fi
    
    echo -e "${GREEN}✅ $client_name 正在运行 (PID: $pid)${NC}"
    return 0
}

# ============================================
# 1. 检查端口
# ============================================
check_port 9999

# ============================================
# 2. 启动 Gateway
# ============================================
echo "🚀 启动 Gateway..."
echo ""

# 在后台启动 Gateway
"$SCRIPT_DIR/start-gateway.sh" > /tmp/gateway.log 2>&1 &
GATEWAY_PID=$!

echo "   Gateway PID: $GATEWAY_PID"
echo "   日志文件: /tmp/gateway.log"
echo ""

# 等待 Gateway 就绪
if ! wait_for_gateway; then
    echo "查看 Gateway 日志:"
    tail -20 /tmp/gateway.log
    exit 1
fi

# ============================================
# 3. 启动 BUY 客户端
# ============================================
echo ""
echo "🚀 启动 BUY 客户端..."
echo ""

"$SCRIPT_DIR/start-buy-client.sh" > /tmp/buy-client.log 2>&1 &
BUY_CLIENT_PID=$!

echo "   BUY 客户端 PID: $BUY_CLIENT_PID"
echo "   日志文件: /tmp/buy-client.log"

if ! check_client_connection "BUY 客户端" $BUY_CLIENT_PID; then
    echo "查看 BUY 客户端日志:"
    tail -20 /tmp/buy-client.log
    exit 1
fi

# ============================================
# 4. 启动 SELL 客户端
# ============================================
echo ""
echo "🚀 启动 SELL 客户端..."
echo ""

"$SCRIPT_DIR/start-sell-client.sh" > /tmp/sell-client.log 2>&1 &
SELL_CLIENT_PID=$!

echo "   SELL 客户端 PID: $SELL_CLIENT_PID"
echo "   日志文件: /tmp/sell-client.log"

if ! check_client_connection "SELL 客户端" $SELL_CLIENT_PID; then
    echo "查看 SELL 客户端日志:"
    tail -20 /tmp/sell-client.log
    exit 1
fi

# ============================================
# 5. 测试结果
# ============================================
echo ""
echo "=========================================="
echo -e "${GREEN}✅ 所有服务已启动${NC}"
echo "=========================================="
echo ""
echo "运行状态："
echo "  - Gateway: 运行中 (PID: $GATEWAY_PID)"
echo "  - BUY 客户端: 运行中 (PID: $BUY_CLIENT_PID)"
echo "  - SELL 客户端: 运行中 (PID: $SELL_CLIENT_PID)"
echo ""
echo "查看日志："
echo "  - Gateway: tail -f /tmp/gateway.log"
echo "  - BUY 客户端: tail -f /tmp/buy-client.log"
echo "  - SELL 客户端: tail -f /tmp/sell-client.log"
echo ""
echo "按 Ctrl+C 停止所有服务"
echo ""

# 等待用户中断或等待一段时间
sleep 10

echo ""
echo "测试运行 10 秒，检查日志以确认连接成功..."

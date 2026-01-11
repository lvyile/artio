#!/bin/bash

# SELL 客户端启动脚本
# 功能：启动 SELL 客户端，连接到本地 Gateway

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CLIENT_DIR="$PROJECT_DIR/clients/cpp"

cd "$CLIENT_DIR"

# ============================================
# 配置参数
# ============================================
HOST="127.0.0.1"           # 本地 Gateway (使用 IP 地址，因为 inet_pton 不支持 localhost)
PORT="9999"
POSS_DUP_FLAG="N"
SENDER_ID="SELL"           # SELL 客户端
TARGET_ID="FGW"            # Gateway 的 TargetCompID (FIX Gateway)
DELIVER_TO_ID="BUY"

# ============================================
# 检查客户端文件
# ============================================
# 检查是否是符号链接到原始客户端目录
if [ -L "$CLIENT_DIR/erpusher_heartbeat" ]; then
    ER_PUSHER_BIN="$CLIENT_DIR/erpusher_heartbeat"
elif [ -f "/Users/lvyile/Desktop/fix/good-case/erpusher_heartbeat" ]; then
    # 如果符号链接不存在，使用原始路径
    ER_PUSHER_BIN="/Users/lvyile/Desktop/fix/good-case/erpusher_heartbeat"
    echo "⚠️  使用原始客户端路径: $ER_PUSHER_BIN"
else
    echo "❌ 错误: 找不到客户端二进制文件"
    echo ""
    echo "请执行以下步骤之一："
    echo "1. 创建符号链接:"
    echo "   cd $CLIENT_DIR"
    echo "   ln -s /Users/lvyile/Desktop/fix/good-case/erpusher_heartbeat ."
    echo "   ln -s /Users/lvyile/Desktop/fix/good-case/erpusher.cpp ."
    echo ""
    echo "2. 或者编译客户端:"
    echo "   cd /Users/lvyile/Desktop/fix/good-case"
    echo "   ./build.sh"
    exit 1
fi

# 检查二进制文件是否存在且可执行
if [ ! -f "$ER_PUSHER_BIN" ] || [ ! -x "$ER_PUSHER_BIN" ]; then
    echo "❌ 错误: 客户端二进制文件不存在或不可执行: $ER_PUSHER_BIN"
    exit 1
fi

# ============================================
# 执行客户端
# ============================================
echo "🚀 启动 SELL 客户端..."
echo "   服务器: $HOST:$PORT"
echo "   SenderCompID: $SENDER_ID"
echo "   TargetCompID: $TARGET_ID"
echo "   模式: 仅发送心跳，保持连接"
echo "   按 Ctrl+C 退出"
echo ""

"$ER_PUSHER_BIN" \
  -h "$HOST" \
  -p "$PORT" \
  --PossDupFlag "$POSS_DUP_FLAG" \
  --sender-id "$SENDER_ID" \
  --target-id "$TARGET_ID" \
  --deliver-to-id "$DELIVER_TO_ID"

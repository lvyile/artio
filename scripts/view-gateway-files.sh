#!/bin/bash

# Gateway 文件查看工具
# 功能：以可读方式查看 buy-sell-gateway 目录下的文件内容

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GATEWAY_DIR="$PROJECT_DIR/buy-sell-gateway"

if [ ! -d "$GATEWAY_DIR" ]; then
    echo "❌ 错误: Gateway 目录不存在: $GATEWAY_DIR"
    echo "   请先启动 Gateway 生成这些文件"
    exit 1
fi

echo "=== Gateway 文件查看工具 ==="
echo "目录: $GATEWAY_DIR"
echo ""

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 查看小文件（文本或简单二进制）
view_small_file() {
    local file=$1
    local desc=$2
    
    echo -e "${BLUE}📄 $desc${NC}"
    echo "文件: $file"
    echo "大小: $(ls -lh "$file" | awk '{print $5}')"
    echo ""
    
    # 尝试显示为文本
    if strings "$file" 2>/dev/null | head -5 | grep -q .; then
        echo "文本内容:"
        strings "$file" | head -5
        echo ""
    fi
    
    # 显示十六进制（前32字节）
    echo "十六进制 (前32字节):"
    hexdump -C "$file" | head -3
    echo ""
    echo "---"
    echo ""
}

# 查看大文件（二进制缓冲区）
view_large_buffer() {
    local file=$1
    local desc=$2
    
    echo -e "${BLUE}💾 $desc${NC}"
    echo "文件: $file"
    echo "大小: $(ls -lh "$file" | awk '{print $5}')"
    echo ""
    
    # 检查是否全为0（未初始化）
    local non_zero=$(od -An -tx1 "$file" | grep -v "^ 00 00 00 00" | head -1)
    if [ -z "$non_zero" ]; then
        echo -e "${YELLOW}⚠️  文件内容全为 0（未初始化或已清空）${NC}"
    else
        echo "文件头部 (前64字节):"
        hexdump -C "$file" | head -4
        echo ""
        echo "文件尾部 (最后64字节):"
        tail -c 64 "$file" | hexdump -C
    fi
    echo ""
    echo "---"
    echo ""
}

# 1. 元数据文件
if [ -f "$GATEWAY_DIR/metadata" ]; then
    view_small_file "$GATEWAY_DIR/metadata" "元数据文件 (Metadata)"
fi

# 2. 引擎信息
if [ -f "$GATEWAY_DIR/engine-info" ]; then
    echo -e "${BLUE}📄 引擎信息文件 (Engine Info)${NC}"
    echo "文件: $GATEWAY_DIR/engine-info"
    echo "大小: $(ls -lh "$GATEWAY_DIR/engine-info" | awk '{print $5}')"
    echo ""
    echo "文本内容:"
    strings "$GATEWAY_DIR/engine-info" 2>/dev/null || cat "$GATEWAY_DIR/engine-info" 2>/dev/null | head -5
    echo ""
    echo "十六进制:"
    hexdump -C "$GATEWAY_DIR/engine-info" | head -3
    echo ""
    echo "---"
    echo ""
fi

# 3. 记录协调器
if [ -f "$GATEWAY_DIR/recording_coordinator" ]; then
    view_small_file "$GATEWAY_DIR/recording_coordinator" "记录协调器 (Recording Coordinator)"
fi

# 4. 重放位置文件
if [ -f "$GATEWAY_DIR/replay-positions-1" ]; then
    echo -e "${BLUE}📊 重放位置文件 1 (Replay Positions 1)${NC}"
    echo "文件: $GATEWAY_DIR/replay-positions-1"
    echo "大小: $(ls -lh "$GATEWAY_DIR/replay-positions-1" | awk '{print $5}')"
    echo ""
    echo "内容 (前128字节):"
    hexdump -C "$GATEWAY_DIR/replay-positions-1" | head -5
    echo ""
    echo "---"
    echo ""
fi

if [ -f "$GATEWAY_DIR/replay-positions-2" ]; then
    echo -e "${BLUE}📊 重放位置文件 2 (Replay Positions 2)${NC}"
    echo "文件: $GATEWAY_DIR/replay-positions-2"
    echo "大小: $(ls -lh "$GATEWAY_DIR/replay-positions-2" | awk '{print $5}')"
    echo ""
    echo "内容 (前128字节):"
    hexdump -C "$GATEWAY_DIR/replay-positions-2" | head -5
    echo ""
    echo "---"
    echo ""
fi

# 5. 序列号文件（大文件）
if [ -f "$GATEWAY_DIR/sequence_numbers_sent" ]; then
    view_large_buffer "$GATEWAY_DIR/sequence_numbers_sent" "发送序列号索引 (Sequence Numbers Sent)"
fi

if [ -f "$GATEWAY_DIR/sequence_numbers_received" ]; then
    view_large_buffer "$GATEWAY_DIR/sequence_numbers_received" "接收序列号索引 (Sequence Numbers Received)"
fi

# 6. 会话ID缓冲区
if [ -f "$GATEWAY_DIR/session_id_buffer" ]; then
    view_large_buffer "$GATEWAY_DIR/session_id_buffer" "会话ID缓冲区 (Session ID Buffer)"
fi

# 7. FIXP ID缓冲区
if [ -f "$GATEWAY_DIR/fixp_id_buffer" ]; then
    view_large_buffer "$GATEWAY_DIR/fixp_id_buffer" "FIXP ID缓冲区 (FIXP ID Buffer)"
fi

echo ""
echo "=== 文件格式说明 ==="
echo ""
echo "📝 文本/简单二进制文件:"
echo "  - metadata: 元数据，包含魔数 0xBEEF"
echo "  - engine-info: 引擎信息，包含主机名等"
echo "  - recording_coordinator: 记录协调器状态"
echo ""
echo "💾 二进制缓冲区文件（内存映射文件）:"
echo "  - sequence_numbers_*: 序列号索引，使用 SBE (Simple Binary Encoding) 格式"
echo "  - session_id_buffer: 会话ID缓冲区，二进制格式"
echo "  - replay-positions-*: 重放位置索引，二进制格式"
echo ""
echo "📖 查看方式:"
echo "  - 小文件: 使用 strings 或 hexdump 查看"
echo "  - 大文件: 使用 hexdump 查看头部和尾部"
echo "  - 格式: 这些文件使用 Artio 自定义的二进制格式，需要专门的工具解析"
echo ""

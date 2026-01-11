# C++ 客户端集成说明

## 客户端位置

原始客户端代码位于：`/Users/lvyile/Desktop/fix/good-case/`

## 集成方式

### 方式 1：创建符号链接（推荐）

在 `clients/cpp/` 目录下创建符号链接，指向原始客户端目录：

```bash
cd clients/cpp
ln -s /Users/lvyile/Desktop/fix/good-case/erpusher_heartbeat .
ln -s /Users/lvyile/Desktop/fix/good-case/erpusher.cpp .
ln -s /Users/lvyile/Desktop/fix/good-case/build.sh .
```

### 方式 2：复制文件

如果需要独立副本，可以复制文件：

```bash
cp /Users/lvyile/Desktop/fix/good-case/erpusher_heartbeat clients/cpp/
cp /Users/lvyile/Desktop/fix/good-case/erpusher.cpp clients/cpp/
cp /Users/lvyile/Desktop/fix/good-case/build.sh clients/cpp/
```

## 编译客户端

如果客户端未编译，需要先编译：

```bash
cd /Users/lvyile/Desktop/fix/good-case
./build.sh
```

这会生成两个可执行文件：
- `erpusher` - 正常模式（发送 ExecutionReport）
- `erpusher_heartbeat` - 仅心跳模式

## 配置说明

客户端启动脚本已配置为：
- **服务器地址**: `localhost:9999`（本地 Gateway）
- **TargetCompID**: `FGW`（FIX Gateway）
- **SenderCompID**: 
  - BUY 客户端：`BUY`
  - SELL 客户端：`SELL`

## 使用方式

使用项目根目录下的启动脚本：

```bash
# 启动 BUY 客户端
./scripts/start-buy-client.sh

# 启动 SELL 客户端
./scripts/start-sell-client.sh
```

## 注意事项

1. 确保 Gateway 已启动（`./scripts/start-gateway.sh`）
2. 客户端需要先编译才能运行
3. 如果客户端路径改变，需要更新启动脚本中的路径

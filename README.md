## 项目概述

这是一个基于 Java + Tendermint ABCI 的极简区块链应用，实现了账户余额管理和签名转账。ABCI 应用通过 gRPC 与 Tendermint Core 通信，状态使用 MapDB 持久化到本地磁盘，支持进程重启后状态恢复。

## 构建与开发命令

生成 protobuf/gRPC 代码并编译：

```bash
mvn clean compile
```

仅生成 protobuf 源码：

```bash
mvn generate-sources
```

打包可执行 fat JAR（跳过测试）：

```bash
mvn clean package -DskipTests
```

运行 ABCI 应用（gRPC 监听 26658 端口）：

```bash
java -jar target/tendermint-abci-1.0-SNAPSHOT.jar
```

运行指定测试类：

```bash
mvn test -Dtest=SignTest
```

## 架构设计

### 通信架构

| 端口  |        通信双方         | 协议                             |
| :---- | :---------------------: | -------------------------------- |
| 26656 | Tendermint ↔ Tendermint | 自定义 P2P 协议（TCP）           |
| 26657 | 外部客户端 → Tendermint | HTTP JSON RPC                    |
| 26658 | Tendermint → ABCI 应用  | gRPC（HTTP/2 + Protobuf 二进制） |

### 核心类职责

- `App.java`：主入口，实例化 `ABCIApp` 并启动 `GrpcServer`。
- `GrpcServer.java`：gRPC 服务端包装，注册 ABCI 服务，端口 26658。
- `ABCIApp.java`：ABCI 接口实现，处理 Tendermint 的 `Info/InitChain/CheckTx/DeliverTx/Query/Commit/BeginBlock/EndBlock` 调用。
- `StateManager.java`：状态管理层，封装 MapDB 读写、转账执行、appHash 计算、区块高度管理。
- `Account.java` / `Transaction.java`：领域模型。交易包含 `nonce` 字段用于防重放和排序。
- `keyUtil.java`：Ed25519 密钥生成、签名、验签工具。
- `ReturnCode.java`：业务错误码枚举（SUCCESS、ADDRESS_NULL、LOW_BALANCE、SIGN_ERROR、NONCE_INVALID、ACCOUNT_NOT_FOUND、SYSTEM_ERROR）。
- `AccountInitializer.java`：独立工具类，生成 Ed25519 密钥对并打印 Base64。
- `ClientDemo.java`：独立演示客户端，支持查询账户、构造签名交易、直接通过 HTTP 发送到 Tendermint。

### 状态存储层（MapDB）

`StateManager` 使用 MapDB 管理以下持久化数据结构：

- `HTreeMap<String, Account> accounts`：账户表，key 为 accountId。自定义 `AccountSerializer` 处理序列化。
- `Atomic.Var<Long> lastBlockHeight`：已提交的最新区块高度。
- `Atomic.Var<byte[]> lastAppHash`：已提交的最新状态哈希。

MapDB 数据库文件：`abci-state.db`（运行目录下）。

## 数据持久化与状态管理

### 状态生命周期

```
Tendermint 调用 initChain（创世）
    └── StateManager.resetState()：清空账户、height=0、appHash=空
    └── 从 genesis.json 的 app_state.accounts 初始化创世账户

出块流程（每区块）：
    BeginBlock(height) → 设 pendingHeight
    DeliverTx(tx)      → 执行转账（改 accounts）
    EndBlock()         → 空实现
    Commit()           → 计算 appHash → 刷盘（db.commit()）
```

### appHash 计算

`Commit()` 时对当前所有账户状态做确定性 SHA256：

1. 按 `accountId` 排序遍历
2. 拼接 `id + name + publicKey + balance + nonce` 的字节
3. 返回 SHA256 摘要

Tendermint 会将 appHash 写入区块头，用于一致性校验和 Replay 验证。

### 重启行为

- **只重启 ABCI 应用**：MapDB 文件自动加载，Tendermint 通过 `Info()` 获取高度和 appHash，发现一致，直接继续工作。
- **只重启 Tendermint**：Tendermint 通过 `Info()` 获取高度，若本地 `storeHeight > appHeight` 会触发 **Replay**（重放历史区块恢复状态）。
- **两边都重启**：状态完全连续，无需删除数据。
- **重新创世（清空重来）**：必须同时删除 `tendermint-home/` 和 `abci-state.db`，然后重新 `tendermint init`。

## ABCI 接口生命周期

| 方法         | 触发时机            | 是否可改状态 | 职责                                   |
| ------------ | ------------------- | ------------ | -------------------------------------- |
| `echo`       | 建立 ABCI 连接时    | 否           | 心跳握手                               |
| `flush`      | 批量请求后          | 否           | 同步屏障                               |
| `info`       | Tendermint 启动时   | 否           | 返回 `lastBlockHeight` + `lastAppHash` |
| `initChain`  | 全新链创世时        | 是           | 加载 genesis 配置，初始化账户          |
| `checkTx`    | 交易进入 mempool 前 | 否           | 验格式、验签、查余额、查 nonce         |
| `deliverTx`  | 区块内逐笔执行      | 是           | 真正执行转账，修改状态                 |
| `beginBlock` | 每个区块开始时      | 是           | 记录当前区块高度                       |
| `endBlock`   | 每个区块结束时      | 是           | 空实现（可扩展验证人更新）             |
| `commit`     | 区块最终确认时      | 是           | **刷盘 + 返回 appHash**                |
| `query`      | 外部查询时          | 否           | 只读查询账户完整信息（含余额和 nonce） |

## 交易与 Nonce 机制

### 交易格式（JSON）

```json
{
  "fromId": "alice",
  "toId": "bob",
  "amount": 100,
  "nonce": 4,
  "signature": "Base64签名"
}
```

### 签名内容

```
fromId:toId:amount:nonce
```

例如：`alice:bob:100:4`

### Nonce 规则

- 每个账户独立维护自己的 `nonce`，从 0 开始。
- 交易中的 `nonce` 必须等于 `from 账户当前 nonce + 1`。
- 转账成功后，`from.nonce` 更新为交易中的 `nonce`。
- **作用**：防交易重放、防 Tendermint txCache 拒绝重复内容、保证执行顺序。

### 多客户端并发

多个客户端同时发交易时可能查询到相同的当前 nonce，导致冲突。标准处理方式是**乐观发送 + 失败重试**：收到 `NONCE_INVALID` 后重新查询并构造新交易。

## Genesis 配置

Tendermint 的 `genesis.json` 支持 `app_state` 字段，ABCI 应用在 `initChain` 时读取。

示例：

```json
{
  "genesis_time": "...",
  "chain_id": "test-chain-xxx",
  "initial_height": "0",
  "validators": [...],
  "app_hash": "",
  "app_state": {
    "accounts": [
      {
        "id": "alice",
        "name": "Alice",
        "publicKey": "MCowBQYDK2VwAyEA...",
        "balance": 1000
      }
    ]
  }
}
```

**操作流程**：

1. 运行 `AccountInitializer` 生成密钥对。
2. 将公钥和初始余额填入 `genesis.json` 的 `app_state.accounts`。
3. `tendermint init --home tendermint-home` 生成基础配置。
4. 用填好的 `genesis.json` 覆盖 `tendermint-home/config/genesis.json`。

## 测试与演示

### 启动顺序

```bash
# 1. 启动 ABCI 应用
java -jar target/tendermint-abci-1.0-SNAPSHOT.jar

# 2. 初始化并启动 Tendermint（另开终端）
tendermint.exe init --home tendermint-home
tendermint.exe node --home tendermint-home --abci grpc --proxy_app tcp://127.0.0.1:26658
```

### 使用 ClientDemo 演示

`ClientDemo` 是一个完整的 Java 客户端，支持：

- `queryAccount(String accountId)`：查询账户完整信息（含 nonce、余额）。
- `transfer(Transaction tx)`：将签名交易通过 HTTP 发送到 Tendermint。

演示流程：

1. 查询 Alice 账户，获取当前余额和 nonce。
2. 构造 `Transaction`，`nonce = account.nonce + 1`。
3. 用 Alice 私钥签名。
4. 发送转账交易，等待区块确认。
5. 再次查询 Alice 和 Bob 的余额，验证变化。

**注意**：如果连续快速执行 ClientDemo 可能因 nonce 冲突失败，建议每次执行前确认上一笔已确认，或在代码中加入重试逻辑。

## 关键依赖

- Java 17
- gRPC 1.22.1 + Protobuf 3.7.1（ABCI 通信）
- MapDB 3.1.0（状态持久化）
- BouncyCastle 1.70（Ed25519 加密）
- Fastjson2 2.0.40（JSON 序列化）
- Logback + SLF4J（日志框架，但代码中仍大量使用 `System.out.println`）
- JUnit 3.8.1（测试，版本较旧）

## 注意事项

- `maven-shade-plugin` 打包时会排除签名文件（`*.SF` / `*.DSA` / `*.RSA`）并合并 gRPC 服务描述符，避免冲突。
- 生成的 protobuf Java 类在 `target/generated-sources/protobuf/` 下，IDE 可能需要标记为源码目录（Maven 编译时已通过 `build-helper-maven-plugin` 自动加入）。
- MapDB 的 `db.commit()` 是状态持久化的唯一刷盘点。如果 `Commit()` 前进程崩溃，状态会回退到上一次 `commit`。
- 当前为**单节点**演示，Tendermint 的共识层、P2P 网络均由 Tendermint Core 提供，ABCI 应用只负责状态机逻辑。
- 项目根目录下已有的 `abci-state.db` 和 `.wal` 文件是 MapDB 运行时数据，重新创世时应一并删除。

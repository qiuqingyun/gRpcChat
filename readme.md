# GRpcChat - 基于gRpc的端到端加密聊天室

## 运行参数

```bash
java -jar gRpcChat-1.0.jar [-para <arg>] 
 Parameters:
 -c,--client          Client mode             
 -s,--server          Server mode
 -i,--ip    <arg>     Connect ip              [default: 127.0.0.1]
 -p,--port  <arg>     Connect port            [default: 50000]
 -n,--name  <arg>     Account name            [default: Random Generation]
 -k,--key   <arg>     Account key file path   [default: Random Generation]
 -h,--help            Print this help message
```

### 最小部署

#### 部署服务器端

```bash
java -jar gRpcChat-1.0.jar -s
```

#### 部署客户端

```bash
java -jar gRpcChat-1.0.jar -c 
```

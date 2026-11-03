# 车机端与 C3 智驾端双向通信协同协议规范

## 一、网络端口矩阵 (Port Matrix)

整个车载通信链路运行于车内 Wi-Fi 局域网内（手机热点或车载 Wi-Fi），**绝对不依赖外部公网互联网**。

| 端口号 | 传输协议 | 监听方 (bind) | 发送方 (sendto) | 报文类型与职责 | 频次与时机 |
| :---: | :---: | :---: | :---: | :--- | :---: |
| **`4210` / `5510`** | UDP 广播/单播 | C3 监听 `4210` / 车机监听 `5510` | 车机广播探针 $\rightarrow$ C3 动态回包 | **设备免配置自动发现与 IP 学习** | 车机启动时广播 5 次，C3 毫秒级应答。 |
| **`7706`** | UDP 单播直推 | **车机本地绑定监听** | **C3 发送目标** | **下行 81 项感知与车辆状态遥测包** | 20Hz 强实时定频推流（每 50ms 一帧）。 |
| **`7705`** | UDP 单播直推 | **C3 本地绑定监听** | **车机发送目标** | **上行超车变道、巡航加减速与参数指令** | 导航触发、按钮点击或微调时即时发送。 |

---

## 二、报文格式与字段契约

### 1. 探针发现契约 (UDP 4210 / 5510)
* **车机广播探针**：`{"cmd":"discover","port":5510}` 及 `c3_ping`
* **C3 应答回包**：
  ```json
  {
    "resp": "c3_pong",
    "device": "comma3",
    "status": "ready",
    "device_id": "c3",
    "auth": true,
    "ip": "192.168.43.128",
    "version": "openpilot-cp-custo"
  }
  ```

### 2. 下行遥测数据包 (C3 $\rightarrow$ 车机, UDP `7706`, 20Hz)
```json
{
  "active": true,
  "v_ego": 27.78,
  "v_ego_kph": 100.0,
  "steer_angle": -0.85,
  "gas_press": false,
  "brake_press": false,
  "engaged": true,
  "v_cruise": 100,
  "lead1": true,
  "drel": 42.5,
  "vrel": -1.2,
  "left_blindspot": false,
  "right_blindspot": false,
  "lidar_behind_vdist_time": 4.25,
  "left_line_type": 1,
  "right_line_type": 1
}
```

### 3. 上行控制指令包 (车机 $\rightarrow$ C3, UDP `7705`, 变道/巡航触发)
```json
{
  "cmd": "OVERTAKE",
  "arg": "LEFT",
  "direction": "LEFT",
  "param": 1,
  "seq": 1024,
  "index": 1024,
  "v_cruise": 100,
  "blinker": 1,
  "timestamp": 1730592000000
}
```
* `param`：三级超车风格（`0`: 激进 TTC $\ge 1.8\text{s}$, `1`: 常规 TTC $\ge 2.5\text{s}$, `2`: 保守 TTC $\ge 3.5\text{s}$）；
* `seq`：单调递增防重放序号，C3 自动丢弃旧包与乱序包。

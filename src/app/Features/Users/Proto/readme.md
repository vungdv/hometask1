| Method                    | Encoding | Content-Type                 | Usage Scenario                                                        |
| ------------------------- | -------- | ---------------------------- | --------------------------------------------------------------------- |
| **1. Connect (Protobuf)** | Protobuf | `application/proto`          | Most performant, binary transport                                     |
| **2. Connect (JSON)**     | JSON     | `application/json`           | Easier to debug, human-readable                                       |
| **3. gRPC-Web**           | Protobuf | `application/grpc-web+proto` | Needed for browser-like environments or intermediaries (e.g. proxies) |

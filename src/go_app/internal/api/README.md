# Connect-go

Compare with other options:

| Approach                             | Transport          | Encoding         | Tools/Servers                         | Client Support (Browser/Native)                          | Performance    | Interop with REST |
| ------------------------------------ | ------------------ | ---------------- | ------------------------------------- | -------------------------------------------------------- | -------------- | ----------------- |
| **1. Connect-go (Connect Protocol)** | HTTP/1.1 or HTTP/2 | Protobuf or JSON | `connect-go`, `connect-web`           | ✅ Browser + Native (via `connect-web`)                  | ✅ High        | ✅ via JSON       |
| **2. gRPC (native)**                 | HTTP/2             | Protobuf         | `grpc-go`, `Grpc.AspNetCore`, etc     | ❌ Browser (no native support) / ✅ Native (C#, Go, etc) | ✅✅ Very High | ❌ Needs wrapper  |
| **3. gRPC-Web**                      | HTTP/1.1 or HTTP/2 | Protobuf         | `connect-go`, `grpcwebproxy`, `envoy` | ✅ Browser (via grpc-web TS) / ⚠️ Native                 | ✅ High        | ❌ No             |
| **4. gRPC-Gateway**                  | HTTP/1.1           | JSON             | `grpc-gateway`, `protobuf-go`         | ✅ Browser + Native                                      | ❌ Lower       | ✅ Native         |
| **5. REST + Protobuf (custom)**      | HTTP/1.1           | Protobuf         | Any HTTP server (Go, C#, etc)         | ⚠️ Manual work (browsers require custom clients)         | ✅ High        | ⚠️ Manual         |
| **6. REST + JSON (OpenAPI)**         | HTTP/1.1           | JSON             | Any server + OpenAPI generator        | ✅ ✅ Fully compatible                                   | ❌ Lower       | ✅ Easy           |

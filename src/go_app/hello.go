package main

import (
	"fmt"
	"go_app/hello"
	"io"
	"net/http"
	"time"

	"google.golang.org/protobuf/proto"
)

func helloHandler(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	body, _ := io.ReadAll(r.Body)

	var req hello.HelloRequest
	err := proto.Unmarshal(body, &req)
	if err != nil {
		http.Error(w, "Invalid Protobuf", http.StatusBadRequest)
		return
	}

	resp := &hello.HelloReply{
		Message: fmt.Sprintf("Hello, %s %s!", req.Name, time.Now()),
	}
	respBytes, _ := proto.Marshal(resp)

	w.Header().Set("Content-Type", "application/x-protobuf")
	w.Write(respBytes)
}

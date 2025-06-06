package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"time"

	"go_app/hello" // Replace with the actual path of generated code

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
		Message: fmt.Sprintf("Hello, %s %s!", req.Name, time.DateTime),
	}
	respBytes, _ := proto.Marshal(resp)

	w.Header().Set("Content-Type", "application/x-protobuf")
	w.Write(respBytes)
}

func main() {
	http.HandleFunc("/hello", helloHandler)
	fmt.Println("Go Protobuf server listening on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}

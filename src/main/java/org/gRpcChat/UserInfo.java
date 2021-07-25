package org.gRpcChat;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;

public class UserInfo {
    public String name;
    public ByteString pk;
    public String skHash;
    public StreamObserver<Pack> stream;
}

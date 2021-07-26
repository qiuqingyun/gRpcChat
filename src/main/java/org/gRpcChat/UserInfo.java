package org.gRpcChat;

import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public class UserInfo {
    private String name = null;
    private boolean online = false;
    private ByteString pk = null;
    private String skHash = null;
    private StreamObserver<Pack> stream = null;

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("-".repeat(10)).append("\n")
                .append("User  : ").append(name).append("\n")
                .append("Status: ").append((online ? "online" : "offline")).append("\n")
                .append("pk    : ");
        try {
            sb.append(Arrays.toString(CleartextKeysetHandle.getKeyset(GRpcUtil.getKeyKeysetHandle(pk)).getKey(0).getKeyData().getValue().toByteArray()));
        } catch (GeneralSecurityException | IOException e) {
            sb.append(pk.toString());
        }
        sb.append("\n")
                .append("skHash: ").append(skHash).append("\n")
                .append("stream: ").append(stream).append("\n")
                .append("-".repeat(10)).append("\n")
        ;
        return sb.toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ByteString getPk() {
        return pk;
    }

    public void setPk(ByteString pk) {
        this.pk = pk;
    }

    public String getSkHash() {
        return skHash;
    }

    public void setSkHash(String skHash) {
        this.skHash = skHash;
    }

    public StreamObserver<Pack> getStream() {
        return stream;
    }

    public void setStream(StreamObserver<Pack> stream) {
        this.stream = stream;
    }

    public boolean isOnline() {
        return online;
    }

    public void login() {
        this.online = true;
    }

    public void logout() {
        this.online = false;
        this.stream = null;
    }
}

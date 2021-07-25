package org.gRpcChat;

import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.JsonKeysetReader;
import com.google.crypto.tink.JsonKeysetWriter;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.proto.KeyData;
import com.google.crypto.tink.proto.Keyset;
import com.google.protobuf.ByteString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Random;

public class GRpcUtil {
    private static final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    //获取当前时间戳
    public static String getTimeStamp() {
        return String.valueOf(new Date().getTime());
    }

    //打印功能列表
    public static void printFunctions() {
        System.out.print("-".repeat(10)
                + "\nFunctions:"
                + "\n#userlist    - Load online user list."
                + "\n#setreceiver - Set communication receiver."
                + "\n#logout      - Logging out of login status."
                + "\n" + "-".repeat(10) + "\n" + "#"
        );
    }

    //获取随机字符串
    public static String getRandomString(int length) {
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        random.setSeed(new Date().getTime());
        for (int i = 0; i < length; i++) {
            int randIndex = random.nextInt(62);
            sb.append(chars.charAt(randIndex));
        }
        return sb.toString();
    }

    //计算字符串的SHA256哈希值
    public static String SHA256(String input) {
        byte[] hash = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return bytesToHex(hash);
    }

    //字节数组转十六进制字符串
    private static String bytesToHex(byte[] hash) {
        if (hash != null) {
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        }
        return null;
    }

    public static ByteString getKeyByteString(KeysetHandle keysetHandle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(baos));
        return ByteString.copyFrom(baos.toByteArray());
    }

    public static KeysetHandle getKeyKeysetHandle(ByteString key) throws GeneralSecurityException, IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(key.toByteArray());
        return CleartextKeysetHandle.read(JsonKeysetReader.withInputStream(bais));
    }

    public static ByteString toByteString(String string) {
        return ByteString.copyFrom(string.getBytes(StandardCharsets.UTF_8));
    }
}

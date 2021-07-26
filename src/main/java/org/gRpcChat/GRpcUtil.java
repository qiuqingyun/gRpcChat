package org.gRpcChat;

import com.google.crypto.tink.*;

import com.google.protobuf.ByteString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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

    //从KeysetHandle格式获取ByteString格式密钥
    public static ByteString getKeyByteString(KeysetHandle keysetHandle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(keysetHandle, BinaryKeysetWriter.withOutputStream(baos));
        return ByteString.copyFrom(baos.toByteArray());
    }

    //从ByteString格式恢复KeysetHandle格式密钥
    public static KeysetHandle getKeyKeysetHandle(ByteString key) throws GeneralSecurityException, IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(key.toByteArray());
        return CleartextKeysetHandle.read(BinaryKeysetReader.withInputStream(bais));
    }

    //将String转换为ByteString格式
    public static ByteString toByteString(String string) {
        return ByteString.copyFrom(string.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytesToFile(byte[] bFile, String fileDest) throws IOException {
        Files.write(Paths.get(fileDest), bFile);
    }

    public static byte[] readBytesFromFile(String filePath) throws IOException {
        return Files.readAllBytes(new File(filePath).toPath());
    }
}

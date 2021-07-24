package org.gRpcChat;

import java.util.Date;

public class GRpcUtil {
    public static String getTimeStamp() {
        return String.valueOf(new Date().getTime());
    }
}

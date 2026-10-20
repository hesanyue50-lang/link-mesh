package com.jiuyue.lanmesh.ui;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成。
 *
 * 信令串是 gzip+Base64 后的 1-2KB 文本，用 L 级纠错 + 600px 以上尺寸，
 * 手机摄像头在中距离可稳定识别；纠错级别不取 H，是为了给数据容量留余量。
 */
public final class QrUtil {

    private QrUtil() {}

    public static Bitmap encode(String content, int size) {
        if (content == null || content.length() == 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                int offset = y * size;
                for (int x = 0; x < size; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, size, 0, 0, size, size);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}

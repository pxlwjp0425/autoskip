package com.laopeng.autoskip;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把规则 JSON 落成一个真实文件，方便用户取走 / 分享 / 归档。
 *
 * <p>落盘位置分两条路：
 * <ul>
 *   <li><b>Android 10（API 29）及以上</b> —— 走 MediaStore 写进公共下载目录
 *       {@code /sdcard/Download/快跳/}。这条路径<b>不需要任何存储权限</b>，
 *       返回的 {@code content://} Uri 可以直接丢给 {@code ACTION_SEND} 分享。</li>
 *   <li><b>Android 9（API 28）及以下</b> —— MediaStore.Downloads 还不存在，
 *       退回 App 自己的外部私有目录 {@code /sdcard/Android/data/<包名>/files/}，
 *       同样不需要权限，但只能给出路径、不支持分享。</li>
 * </ul>
 *
 * <p>之所以不在老系统上走公共目录：那需要 {@code WRITE_EXTERNAL_STORAGE} 运行时权限；
 * 而分享私有文件在现代 Android 上必须有 FileProvider，本工程是<b>零第三方依赖</b>的，
 * 用不了 androidx 那个。为一条在老设备上才走到的支路引入依赖不划算。
 */
public class RuleExport {

    /** 公共下载目录下的子目录名。 */
    private static final String DIR_NAME = "快跳";
    private static final String MIME = "application/json";

    public static final class Result {
        /** 落盘后可读的 Uri；失败为 null。 */
        public Uri uri;
        /** 给人看的完整路径；失败为 null。 */
        public String path;
        /** 文件大小（字节）。 */
        public long size;
        /** 能否直接分享（老系统上为 false）。 */
        public boolean shareable;
        /** 失败原因；成功为 null。 */
        public String error;
    }

    private RuleExport() {
    }

    /**
     * 写出规则文件。
     *
     * @param ctx     上下文
     * @param json    规则 JSON 文本
     * @param baseName 文件名主体（会自动补时间戳与 .json 后缀）
     */
    public static Result write(Context ctx, String json, String baseName) {
        Result r = new Result();
        if (json == null || json.trim().length() == 0) {
            r.error = "没有可导出的规则";
            return r;
        }
        String name = baseName + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".json";
        byte[] data;
        try {
            data = json.getBytes("UTF-8");
        } catch (Exception e) {
            r.error = "编码失败：" + e.getMessage();
            return r;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return writePublic(ctx, data, name);
        }
        return writeAppPrivate(ctx, data, name);
    }

    /** Android 10+：写进公共下载目录，无需权限。 */
    private static Result writePublic(Context ctx, byte[] data, String name) {
        Result r = new Result();
        ContentResolver cr = ctx.getContentResolver();
        Uri target = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + DIR_NAME);
            // 先标 pending 再写，避免文件管理器在写一半时读到残缺的 JSON
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (target == null) {
                r.error = "系统拒绝了写下载目录的请求";
                return r;
            }
            OutputStream os = cr.openOutputStream(target, "w");
            if (os == null) {
                r.error = "无法打开输出流";
                return r;
            }
            os.write(data);
            os.flush();
            os.close();

            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(target, cv, null, null);

            r.uri = target;
            r.size = data.length;
            r.shareable = true;
            r.path = "/sdcard/" + Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/" + name;
            return r;
        } catch (Exception e) {
            if (target != null) {
                try {
                    cr.delete(target, null, null);
                } catch (Exception ignored) {
                }
            }
            r.error = e.getClass().getSimpleName() + "：" + e.getMessage();
            return r;
        }
    }

    /** Android 9 及以下：写 App 外部私有目录，无需权限但不能分享。 */
    private static Result writeAppPrivate(Context ctx, byte[] data, String name) {
        Result r = new Result();
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            r.error = "外部存储不可用";
            return r;
        }
        File out = new File(dir, name);
        try {
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.flush();
            fos.close();
            r.uri = Uri.fromFile(out);
            r.path = out.getAbsolutePath();
            r.size = out.length();
            r.shareable = false;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + "：" + e.getMessage();
            return r;
        }
    }
}

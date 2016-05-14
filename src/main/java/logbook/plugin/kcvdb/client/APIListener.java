package logbook.plugin.kcvdb.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import logbook.internal.ThreadManager;
import logbook.kcvdb.client.ApiData;
import logbook.kcvdb.client.GzipSender;
import logbook.proxy.ContentListenerSpi;
import logbook.proxy.RequestMetaData;
import logbook.proxy.ResponseMetaData;

public class APIListener implements ContentListenerSpi {

    /** KCVDBのセッションを管理する */
    private int tokenHashCode;

    /** KCVDBへ送信するクラス */
    private final GzipSender sender = new GzipSender();

    public APIListener() {
        ThreadManager.getExecutorService().scheduleWithFixedDelay(this.sender::send, 1, 10, TimeUnit.SECONDS);
    }

    @Override
    public boolean test(RequestMetaData requestMetaData) {
        String uri = requestMetaData.getRequestURI();
        return uri.startsWith("/kcsapi/");
    }

    @Override
    public void accept(RequestMetaData request, ResponseMetaData response) {
        try {
            // トークンが変わった場合にセッションを作りなおす
            int tokenHashCode = request.getParameterMap().get("api_token").iterator().next().hashCode();
            if (this.tokenHashCode != tokenHashCode) {
                this.tokenHashCode = tokenHashCode;
                this.sender.regenerateSession();
            }

            // Dateフィールド
            String httpDate = response.getHeaders()
                    .get("Date")
                    .iterator()
                    .next();
            // リクエストボディ
            String requestBody = request.getRequestBody()
                    .map(APIListener::toString)
                    .orElse("");
            // 絶対URL
            String requestUri = request.getRequestURL();
            // レスポンスのJSONを復号します
            InputStream stream = response.getResponseBody().get();
            // Check header
            int header = (stream.read() | (stream.read() << 8));
            stream.reset();
            if (header == GZIPInputStream.GZIP_MAGIC) {
                stream = new GZIPInputStream(stream);
            }
            String responseBody = toString(stream);
            // レスポンスのステータスコード
            int statusCode = response.getStatus();

            // 送信データ
            ApiData data = ApiData.createBuilder()
                    .setHttpDate(httpDate)
                    .setLocalTime(ZonedDateTime.now())
                    .setRequestBody(requestBody)
                    .setRequestUri(requestUri)
                    .setResponseBody(responseBody)
                    .setStatusCode(statusCode)
                    .build();

            this.sender.add(data);

        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toString(InputStream in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        int len = 0;
        try {
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new String(out.toByteArray());
    }
}

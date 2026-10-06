import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.regex.Pattern;

/** Bounded unsigned clock probes: no credentials, order endpoints or runtime configuration changes.
 * Run: java script/quant/BinanceClockProbe.java [rounds:1..6]. Ports match local verification profiles.
 */
class BinanceClockProbe {
    static String category(Throwable e) {
        for(int n=0;e!=null&&n<8;n++,e=e.getCause()) {
            if(e instanceof HttpConnectTimeoutException)return "CONNECT_TIMEOUT";
            if(e instanceof HttpTimeoutException)return "REQUEST_TIMEOUT";
            if(e instanceof javax.net.ssl.SSLException)return "TLS_FAILURE";
            if(e instanceof UnknownHostException)return "DNS_FAILURE";
            if(e instanceof ConnectException)return "CONNECT_FAILURE";
            if(e instanceof InterruptedException)return "INTERRUPTED";
        }
        return "IO_FAILURE";
    }
    public static void main(String[] args) throws Exception {
        int rounds=args.length==0?4:Integer.parseInt(args[0]);
        if(rounds<1||rounds>6)throw new IllegalArgumentException("Rounds must be 1..6");
        var time=Pattern.compile("\"serverTime\"\\s*:\\s*(\\d+)");
        try(var privateRoute=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",3067))).build();
            var marketRoute=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",3066))).build()) {
            for(int round=1;round<=rounds;round++)for(int port:new int[]{3067,3066}) {
                long start=System.nanoTime();int status=0;boolean received=false,valid=false;String failure="NONE";
                try {
                    var request=HttpRequest.newBuilder(URI.create("https://api.binance.com/api/v3/time")).timeout(Duration.ofSeconds(15)).header("Accept","application/json").header("User-Agent","quant-platform/1.0").GET().build();
                    var response=(port==3067?privateRoute:marketRoute).send(request,HttpResponse.BodyHandlers.ofString());
                    received=true;status=response.statusCode();var match=time.matcher(response.body());
                    valid=status==200&&match.find()&&Long.parseLong(match.group(1))>0;
                } catch(java.io.IOException e){failure=category(e);} catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                long millis=(System.nanoTime()-start)/1_000_000;
                System.out.printf("{\"checkedAt\":\"%s\",\"round\":%d,\"proxyPort\":%d,\"elapsedMillis\":%d,\"responseReceived\":%s,\"httpStatus\":%d,\"validServerTime\":%s,\"withinClockRttBound\":%s,\"failureKind\":\"%s\"}%n",OffsetDateTime.now(ZoneOffset.ofHours(8)),round,port,millis,received,status,valid,valid&&millis<=2500,failure);
            }
        }
    }
}

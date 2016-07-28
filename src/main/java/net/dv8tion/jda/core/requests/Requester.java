/*
 *     Copyright 2015-2016 Austin Keener & Michael Ritter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.core.requests;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.BaseRequest;
import com.mashape.unirest.request.GetRequest;
import com.mashape.unirest.request.HttpRequest;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.function.Function;

public class Requester
{
    //TODO: Readd log, set useragent from JDAVersion file, get token from JDA (in exec method)
    public static String USER_AGENT = "JDA DiscordBot (https://github.com/DV8FromTheWorld/JDA, v3.0 BETA)";
    public static final String DISCORD_API_PREFIX = "https://discordapp.com/api/";
    public static final String authToken = "";

    private Map<String, Bucket> ratelimits = new HashMap<>();

    public Requester()
    {
    }

    public Response post(String url, JSONObject body, String... buckets)
    {
        return post(url, null, buckets);
    }

    public Response post(String url, JSONObject body, Function<String, String> bucketTransform, String... buckets)
    {
        return exec(new Request(
                addHeaders(Unirest.post(url)).body(body.toString()),
                false, bucketTransform, buckets));
    }


    //TODO: See blocks below
    /*
        Async methods -> have the async thread call exec(Request)
     */

    /*
        Similarly all other rest endpoints
     */

    /*
        Missing: Async thread (queue, just calls exec(Request)
        Missing: 2nd Thread that just sleeps for min(bucket-lock) and requeues bucket to async sender once timeout is over
     */

   private synchronized Response exec(Request request)
    {
        String bucket;
        synchronized (ratelimits)
        {
            bucket = Arrays.stream(request.buckets).filter(b -> ratelimits.keySet().contains(b)).findAny().orElse(null);
            if (bucket != null)
            {
                if (request.isAsync)
                {
                    ratelimits.get(bucket).queue.add(request);
                    return null;
                }
                else
                {
                    throw new RateLimitedException(bucket);
                }
            }
        }

        HttpResponse<String> ret = null;
        try
        {
//            String dbg = String.format("Requesting %s -> %s\n\tPayload: %s\n\tResponse: ", request.getHttpRequest().getHttpMethod().name(),
//                    request.getHttpRequest().getUrl(), ((request instanceof RequestBodyEntity) ? ((RequestBodyEntity) request).getBody().toString() : "None"));
            ret = request.request.asString();
            if (ret.getBody() != null && ret.getBody().startsWith("<"))
            {
//                LOG.debug(String.format("Requesting %s -> %s returned HTML... retrying", request.getHttpRequest().getHttpMethod().name(), request.getHttpRequest().getUrl()));
                try
                {
                    Thread.sleep(50);
                }
                catch (InterruptedException ignored) {}
                ret = request.request.asString();
            }

            Response response = new Response(ret.getStatus(), ret.getBody());
            if (response.isRateLimit())
            {
                bucket = response.getObject().getString("bucket");
                if(request.bucketTransform != null)
                    bucket = request.bucketTransform.apply(bucket);
                long waitUntil = System.currentTimeMillis() + response.getObject().getLong("retryAfter");
                synchronized (ratelimits)
                {
                    if (request.isAsync)
                    {
                        ratelimits.put(bucket, new Bucket(waitUntil, request));
                    }
                    else
                    {
                        ratelimits.put(bucket, new Bucket(waitUntil));
                        throw new RateLimitedException(bucket);
                    }
                }
            }
//            LOG.trace(dbg + response.code + ": " + response.responseText);
            return response;
        }
        catch (UnirestException e)
        {
//            if (LOG.getEffectiveLevel().compareTo(SimpleLog.Level.DEBUG) != 1)
//            {
//                LOG.log(e);
//            }
            return new Response(e);
        }
    }

    private <T extends HttpRequest> T addHeaders(T request)
    {
        //adding token to all requests to the discord api or cdn pages
        //can't check for startsWith(DISCORD_API_PREFIX) due to cdn endpoints
        if (authToken != null && request.getUrl().contains("discordapp.com"))
        {
            request.header("authorization", authToken);
        }
        if (!(request instanceof GetRequest))
        {
            request.header("Content-Type", "application/json");
        }
        request.header("user-agent", USER_AGENT);
        request.header("Accept-Encoding", "gzip");
        return request;
    }

    public static class Response {
        public static final int connectionErrCode = -1;
        public final Exception exception;
        public final int code;
        public final String responseText;

        protected Response(int code, String response)
        {
            this.code = code;
            this.responseText = response;
            this.exception = null;
        }

        protected Response(Exception exception)
        {
            this.code = connectionErrCode;
            this.responseText = null;
            this.exception = exception;
        }

        public boolean isOk()
        {
            return code > 199 && code < 300;
        }

        public boolean isRateLimit()
        {
            return code == 429;
        }

        public JSONObject getObject()
        {
            try
            {
                return responseText == null ? null : new JSONObject(responseText);
            }
            catch (JSONException ex)
            {
                return null;
            }
        }

        public JSONArray getArray()
        {
            try
            {
                return responseText == null ? null : new JSONArray(responseText);
            }
            catch (JSONException ex)
            {
                return null;
            }
        }

        public String toString()
        {
            return exception == null ? "HTTPResponse[" + code + ": " + responseText + ']'
                    : "HTTPException[" + exception.getMessage() + ']';
        }
    }

    private static class Request
    {
        private final BaseRequest request;
        private final boolean isAsync;
        private final Function<String, String> bucketTransform;
        private final String[] buckets;

        public Request(BaseRequest request, boolean isAsync, Function<String, String> bucketTransform, String[] buckets)
        {
            this.request = request;
            this.isAsync = isAsync;
            this.bucketTransform = bucketTransform;
            this.buckets = buckets;
        }
    }

    private static class Bucket
    {
        private final long waitUntil;
        private final Queue<Request> queue = new LinkedList<>();

        public Bucket(long waitUntil)
        {
            this.waitUntil = waitUntil;
        }

        public Bucket(long waitUntil, Request initialRequest)
        {
            this.waitUntil = waitUntil;
            queue.add(initialRequest);
        }
    }
}
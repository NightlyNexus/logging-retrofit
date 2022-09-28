package com.nightlynexus.retrofit.logging;

import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Invocation;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.Body;

/**
 * A CallAdapter.Factory that intercepts calls' synchronous executions and asynchronously called
 * callbacks and logs the responses and failures to the given {@link Logger}.
 */
public final class LoggingCallAdapterFactory extends CallAdapter.Factory {
  /**
   * A logger for the results of calls.
   * <p>Note that these logger methods are called on the thread provided by OkHttp's dispatcher.
   * It is an error to mutate the call from these methods.
   */
  public interface Logger {
    Object UNBUILT_REQUEST_BODY = new Object();

    /**
     * @param requestBody The object supplied to the {@link Body} Retrofit service method parameter
     *                    or null if there is no such parameter.
     */
    <T> void onResponse(Call<T> call, Object requestBody, Response<T> response);

    /**
     * @param requestBody The object supplied to the {@link Body} Retrofit service method parameter,
     *                    null if there is no such parameter, or {@link #UNBUILT_REQUEST_BODY} if
     *                    the request failed to be built.
     */
    <T> void onFailure(Call<T> call, Object requestBody, Throwable t);
  }

  final Logger logger;

  public LoggingCallAdapterFactory(Logger logger) {
    this.logger = logger;
  }

  /**
   * Reads a {@link ResponseBody} as a string or {@code null} if the error message is not plain
   * text. This is useful for logging error bodies. Consumes the {@code errorBody}.
   */
  public static String errorMessage(ResponseBody errorBody) throws IOException {
    if (errorBody.contentLength() == 0) {
      return "";
    }
    Buffer buffer = new Buffer();
    buffer.writeAll(errorBody.source());
    if (!isPlaintext(buffer)) {
      return null;
    }
    // ResponseBody reads the BOM (if it exists) for us.
    return ResponseBody.create(buffer, errorBody.contentType(), buffer.size()).string();
  }

  /**
   * Returns true if the body in question probably contains human readable text. Uses a small sample
   * of code points to detect unicode control characters commonly used in binary file signatures.
   */
  static boolean isPlaintext(Buffer buffer) {
    try {
      Buffer prefix = new Buffer();
      long byteCount = buffer.size() < 64 ? buffer.size() : 64;
      buffer.copyTo(prefix, 0, byteCount);
      for (int i = 0; i < 16; i++) {
        if (prefix.exhausted()) {
          break;
        }
        int codePoint = prefix.readUtf8CodePoint();
        if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
          return false;
        }
      }
      return true;
    } catch (EOFException e) {
      return false; // Truncated UTF-8 sequence.
    }
  }

  @Override
  public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
    CallAdapter<?, ?> delegate = retrofit.nextCallAdapter(this, returnType, annotations);
    return new LoggingCallAdapter<>(delegate, logger);
  }

  static final class LoggingCallAdapter<R, T> implements CallAdapter<R, T> {
    final CallAdapter<R, T> delegate;
    final Logger logger;

    LoggingCallAdapter(CallAdapter<R, T> delegate, Logger logger) {
      this.delegate = delegate;
      this.logger = logger;
    }

    @Override public Type responseType() {
      return delegate.responseType();
    }

    @Override public T adapt(Call<R> call) {
      return delegate.adapt(new LoggingCall<>(logger, call));
    }
  }

  static final class LoggingCall<R> implements Call<R> {
    final Logger logger;
    final Call<R> delegate;

    LoggingCall(Logger logger, Call<R> delegate) {
      this.logger = logger;
      this.delegate = delegate;
    }

    void logResponse(Object requestBody, Response<R> response) {
      if (response.isSuccessful()) {
        logger.onResponse(this, requestBody, response);
      } else {
        ResponseBody errorBody = response.errorBody();
        BufferedSource peekedErrorBodySource = errorBody.source().peek();
        ResponseBody peekedResponseBody = ResponseBody.create(peekedErrorBodySource,
            errorBody.contentType(), errorBody.contentLength());
        Response<R> peekedResponse = Response.error(peekedResponseBody, response.raw());
        logger.onResponse(this, requestBody, peekedResponse);
      }
    }

    Object requestBody() {
      Request request;
      try {
        // Build the request if it has not been built yet.
        request = delegate.request();
      } catch (Throwable t) {
        // The execute or enqueue call that immediately follows this requestBody call will handle
        // the Throwable.
        return Logger.UNBUILT_REQUEST_BODY;
      }
      Invocation invocation = request.tag(Invocation.class);
      if (invocation == null) {
        throw new NullPointerException("Missing Invocation tag. The custom Call.Factory needs " +
            "to create a Calls with Requests that include the Invocation tag.");
      }
      Parameter[] parameters = invocation.method().getParameters();
      for (int i = 0; i < parameters.length; i++) {
        Parameter parameter = parameters[i];
        if (parameter.getAnnotation(Body.class) != null) {
          return invocation.arguments().get(i);
        }
      }
      return null;
    }

    @Override public void enqueue(Callback<R> callback) {
      Object requestBody = requestBody();
      delegate.enqueue(new Callback<R>() {
        @Override public void onResponse(Call<R> call, Response<R> response) {
          logResponse(requestBody, response);
          callback.onResponse(call, response);
        }

        @Override public void onFailure(Call<R> call, Throwable t) {
          logger.onFailure(call, requestBody, t);
          callback.onFailure(call, t);
        }
      });
    }

    @Override public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override public Response<R> execute() throws IOException {
      Response<R> response;
      Object requestBody = requestBody();
      try {
        response = delegate.execute();
      } catch (Throwable t) {
        if (!isFatal(t)) {
          logger.onFailure(this, requestBody, t);
        }
        throw t;
      }
      logResponse(requestBody, response);
      return response;
    }

    // https://github.com/square/retrofit/blob/parent-2.5.0/
    // retrofit/src/main/java/retrofit2/Utils.java#L520
    static boolean isFatal(Throwable e) {
      return e instanceof VirtualMachineError
          || e instanceof ThreadDeath
          || e instanceof LinkageError;
    }

    @Override public void cancel() {
      delegate.cancel();
    }

    @Override public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override public Call<R> clone() {
      return new LoggingCall<>(logger, delegate.clone());
    }

    @Override public Request request() {
      return delegate.request();
    }

    @Override
    public Timeout timeout() {
      return delegate.timeout();
    }
  }
}

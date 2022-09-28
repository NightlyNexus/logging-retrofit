package com.nightlynexus.retrofit.logging;

import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

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
    <T> void onResponse(Call<T> call, Response<T> response);

    <T> void onFailure(Call<T> call, Throwable t);
  }

  private final Logger logger;

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
  private static boolean isPlaintext(Buffer buffer) {
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

  private static final class LoggingCallAdapter<R, T> implements CallAdapter<R, T> {
    private final CallAdapter<R, T> delegate;
    private final Logger logger;

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

  private static final class LoggingCall<R> implements Call<R> {
    final Logger logger;
    private final Call<R> delegate;

    LoggingCall(Logger logger, Call<R> delegate) {
      this.logger = logger;
      this.delegate = delegate;
    }

    void logResponse(Response<R> response) {
      if (response.isSuccessful()) {
        logger.onResponse(this, response);
      } else {
        ResponseBody errorBody = response.errorBody();
        BufferedSource peekedErrorBodySource = errorBody.source().peek();
        ResponseBody peekedResponseBody = ResponseBody.create(peekedErrorBodySource,
            errorBody.contentType(), errorBody.contentLength());
        Response<R> peekedResponse = Response.error(peekedResponseBody, response.raw());
        logger.onResponse(this, peekedResponse);
      }
    }

    @Override public void enqueue(final Callback<R> callback) {
      delegate.enqueue(new Callback<>() {
        @Override public void onResponse(Call<R> call, Response<R> response) {
          logResponse(response);
          callback.onResponse(call, response);
        }

        @Override public void onFailure(Call<R> call, Throwable t) {
          logger.onFailure(call, t);
          callback.onFailure(call, t);
        }
      });
    }

    @Override public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override public Response<R> execute() throws IOException {
      Response<R> response;
      try {
        response = delegate.execute();
      } catch (Throwable t) {
        if (!isFatal(t)) {
          logger.onFailure(this, t);
        }
        throw t;
      }
      logResponse(response);
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

package com.nightlynexus.retrofit.logging;

import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
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
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  /**
   * A logger for the results of calls.
   * Note that these logger methods are called on the thread provided by OkHttp's dispatcher.
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
   * Reads a {@link ResponseBody} as a string. This is useful for logging error bodies.
   * Clones the source's {@link Buffer}, so that the body is not consumed.
   * Warning: Error bodies can be very large because they can come from unexpected sources.
   * Only call this method if you are sure you can buffer the entire body into memory.
   */
  public static String errorMessage(ResponseBody errorBody) throws IOException {
    if (errorBody.contentLength() == 0) {
      return "";
    }
    BufferedSource source = errorBody.source();
    source.request(Long.MAX_VALUE); // Buffer the entire body.
    Buffer buffer = source.buffer();
    if (!isPlaintext(buffer)) {
      return "Error body is not plain text.";
    }
    MediaType contentType = errorBody.contentType();
    Charset charset = contentType == null ? UTF_8 : contentType.charset(UTF_8);
    return buffer.clone().readString(charset);
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
        Buffer buffer = response.errorBody().source().buffer();
        long bufferByteCount = buffer.size();
        logger.onResponse(this, response);
        if (bufferByteCount != buffer.size()) {
          throw new IllegalStateException(String.format(Locale.US,
              "Do not consume the error body. Bytes before: %1$d. Bytes after: %2$d.",
              bufferByteCount, buffer.size()));
        }
      }
    }

    @Override public void enqueue(final Callback<R> callback) {
      delegate.enqueue(new Callback<R>() {
        @Override public void onResponse(Call<R> call, Response<R> response) {
          logResponse(response);
          callback.onResponse(call, response);
        }

        @Override public void onFailure(Call<R> call, Throwable t) {
          // Retrofit 2.3.0 and prior passes fatal errors in here instead of throwing in enqueue.
          // Log these fatal errors in Retrofit 2.3.0 and prior
          // because there is no other way for users to catch them.
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
        // Retrofit 2.3.0 and prior log fatal errors in enqueue, so log them here for symmetry.
        // Retrofit 2.4.0 and newer don't log fatal errors
        // because fatal error are thrown in enqueue instead of being passed to the callback.
        if (!(RETROFIT_THROWS_FATAL_ERRORS && isFatal(t))) {
          logger.onFailure(this, t);
        }
        throw t;
      }
      logResponse(response);
      return response;
    }

    // TODO: Make this true when we depend on 2.4.0.
    // TODO: If a consumer forces the version lower,
    // TODO: then he will log fatal errors in enqueue but not in execute.
    // TODO: So, don't force the version lower!
    // Retrofit 2.4.0 and newer.
    private static boolean RETROFIT_THROWS_FATAL_ERRORS = false;

    // TODO: Replace with the tag on Retrofit 2.4.0 when it is released.
    //https://github.com/square/retrofit/blob/fc1b11249faefc4ef21a576e34e457529ea9fa9d/retrofit/
    // src/main/java/retrofit2/Utils.java#L500
    private static boolean isFatal(Throwable e) {
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
  }
}

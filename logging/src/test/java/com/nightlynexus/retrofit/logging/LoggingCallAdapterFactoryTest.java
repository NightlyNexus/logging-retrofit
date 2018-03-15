package com.nightlynexus.retrofit.logging;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.BufferedSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public final class LoggingCallAdapterFactoryTest {
  @Test public void errorMessageHelperDoesNotConsumeErrorBody() throws IOException {
    ResponseBody errorBody = ResponseBody.create(null, "This request failed.");
    BufferedSource source = errorBody.source();
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody)).isEqualTo("This request failed.");
    assertThat(source.buffer().size()).isEqualTo(20);
    assertThat(source.exhausted()).isFalse();
    String errorBodyUtf8 = source.readUtf8();
    assertThat(errorBodyUtf8).isEqualTo("This request failed.");
    assertThat(source.exhausted()).isTrue();
  }

  @Test public void errorMessageHelperReturnsEmptyStringForEmptyBody() throws IOException {
    ResponseBody errorBody = ResponseBody.create(null, new byte[0]);
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody)).isEmpty();
  }

  @Test public void errorMessageHelperChecksForPlainText() throws IOException {
    ResponseBody errorBody = ResponseBody.create(null, String.valueOf((char) 0x9F));
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody))
        .isEqualTo("Error body is not plain text.");
  }

  private interface Service {
    @GET("/") Call<String> getString();

    @POST("/{a}") Call<Void> postRequestBody(@Path("a") Object a);
  }

  @Test public void disallowsConsumingErrorBody() throws IOException {
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                try {
                  response.errorBody().source().readByte();
                } catch (IOException e) {
                  throw new AssertionError(e);
                }
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                throw new AssertionError(t);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse().setResponseCode(400).setBody("This request failed."));
    try {
      service.getString().execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "Do not consume the error body. Bytes before: 20. Bytes after: 19.");
    }
  }

  @Test public void enqueueLogsOnResponse() throws InterruptedException {
    MockWebServer server = new MockWebServer();
    final CountDownLatch latch = new CountDownLatch(1);
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                latch.countDown();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                throw new AssertionError();
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    service.getString().enqueue(new Callback<String>() {
      @Override public void onResponse(Call<String> call, Response<String> response) {
      }

      @Override public void onFailure(Call<String> call, Throwable t) {
        throw new AssertionError();
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  @Test public void executeLogsOnResponse() throws Exception {
    MockWebServer server = new MockWebServer();
    final AtomicBoolean onResponseCalled = new AtomicBoolean();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                onResponseCalled.set(true);
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                throw new AssertionError();
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    service.getString().execute();
    assertThat(onResponseCalled.get()).isTrue();
  }

  @Test public void enqueueLogsOnFailure() throws InterruptedException {
    MockWebServer server = new MockWebServer();
    final CountDownLatch latch = new CountDownLatch(1);
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                latch.countDown();
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    service.getString().enqueue(new Callback<String>() {
      @Override public void onResponse(Call<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(Call<String> call, Throwable t) {
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  @Test public void executeLogsOnFailure() throws Exception {
    MockWebServer server = new MockWebServer();
    final AtomicBoolean onFailureCalled = new AtomicBoolean();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                onFailureCalled.set(true);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    try {
      service.getString().execute();
      throw new AssertionError();
    } catch (IOException e) {
      assertThat(onFailureCalled.get()).isTrue();
    }
  }

  @Test public void enqueueLogsOnFailureExceptions() throws Exception {
    MockWebServer server = new MockWebServer();
    final CountDownLatch latch = new CountDownLatch(1);
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                RuntimeException runtimeException = (RuntimeException) t;
                assertThat(runtimeException).hasMessageThat().isEqualTo("Broken!");
                latch.countDown();
              }
            }))
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations, Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    service.getString().enqueue(new Callback<String>() {
      @Override public void onResponse(Call<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(Call<String> call, Throwable t) {
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  @Test public void executeLogsOnFailureExceptions() throws Exception {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations, Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    try {
      service.getString().execute();
      throw new AssertionError();
    } catch (RuntimeException ignored) {
    }
    assertThat(failureRef.get()).isInstanceOf(RuntimeException.class);
    assertThat(failureRef.get()).hasMessageThat().isEqualTo("Broken!");
  }

  // TODO: Remove. onFailure should not be called. https://github.com/square/retrofit/pull/2692
  @Test public void enqueueDoesNotLogOnFailureExceptionsFatal() throws Exception {
    MockWebServer server = new MockWebServer();
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations, Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new OutOfMemoryError("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    service.getString().enqueue(new Callback<String>() {
      @Override public void onResponse(Call<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(Call<String> call, Throwable t) {
        latch.countDown();
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();
    assertThat(failureRef.get()).isNull();
  }

  // TODO: Remove? Duplicate of executeDoesNotLogRequestCreationFailureFatal() in practice.
  // Exists as a pair to enqueueDoesNotLogOnFailureExceptionsFatal().
  @Test public void executeDoesNotLogOnFailureExceptionsFatal() throws Exception {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations, Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new OutOfMemoryError("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    try {
      service.getString().execute();
      throw new AssertionError();
    } catch (OutOfMemoryError ignored) {
    }
    assertThat(failureRef.get()).isNull();
  }

  @Test public void enqueueLogsRequestCreationFailure() {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    server.enqueue(new MockResponse());

    Object a = new Object() {
      @Override public String toString() {
        throw new RuntimeException("Broken!");
      }
    };
    Call<Void> call = service.postRequestBody(a);
    call.enqueue(new Callback<Void>() {
      @Override public void onResponse(Call<Void> call, Response<Void> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(Call<Void> call, Throwable t) {
      }
    });
    assertThat(failureRef.get()).isInstanceOf(RuntimeException.class);
    assertThat(failureRef.get()).hasMessageThat().isEqualTo("Broken!");
  }

  @Test public void executeLogsRequestCreationFailure() throws IOException {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    server.enqueue(new MockResponse());

    Object a = new Object() {
      @Override public String toString() {
        throw new RuntimeException("Broken!");
      }
    };
    Call<Void> call = service.postRequestBody(a);
    try {
      call.execute();
      throw new AssertionError();
    } catch (RuntimeException ignored) {
    }
    assertThat(failureRef.get()).isInstanceOf(RuntimeException.class);
    assertThat(failureRef.get()).hasMessageThat().isEqualTo("Broken!");
  }

  @Test public void enqueueDoesNotLogRequestCreationFailureFatal() throws InterruptedException {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    server.enqueue(new MockResponse());

    Object a = new Object() {
      @Override public String toString() {
        throw new OutOfMemoryError("Broken!");
      }
    };
    Call<Void> call = service.postRequestBody(a);
    try {
      call.enqueue(new Callback<Void>() {
        @Override public void onResponse(Call<Void> call, Response<Void> response) {
          throw new AssertionError();
        }

        @Override public void onFailure(Call<Void> call, Throwable t) {
          throw new AssertionError();
        }
      });
      throw new AssertionError();
    } catch (OutOfMemoryError ignored) {
    }
    assertThat(failureRef.get()).isNull();
  }

  @Test public void executeDoesNotLogRequestCreationFailureFatal() throws IOException {
    MockWebServer server = new MockWebServer();
    final AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                failureRef.set(t);
              }
            }))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    server.enqueue(new MockResponse());

    Object a = new Object() {
      @Override public String toString() {
        throw new OutOfMemoryError("Broken!");
      }
    };
    Call<Void> call = service.postRequestBody(a);
    try {
      call.execute();
      throw new AssertionError();
    } catch (OutOfMemoryError ignored) {
    }
    assertThat(failureRef.get()).isNull();
  }

  @Test public void delegatesCallAdapter() {
    MockWebServer server = new MockWebServer();
    AtomicBoolean delegateAdaptCalled = new AtomicBoolean();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                throw new AssertionError();
              }

              @Override public <T> void onFailure(Call<T> call, Throwable t) {
                throw new AssertionError();
              }
            }))
        .addCallAdapterFactory(new TestCallAdapterFactory(delegateAdaptCalled))
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    server.enqueue(new MockResponse());
    service.getString();
    assertThat(delegateAdaptCalled.get()).isTrue();
  }

  private static final class TestCallAdapterFactory extends CallAdapter.Factory {
    final AtomicBoolean delegateAdaptCalled;

    TestCallAdapterFactory(AtomicBoolean delegateAdaptCalled) {
      this.delegateAdaptCalled = delegateAdaptCalled;
    }

    @Override public CallAdapter<?, ?> get(final Type returnType, Annotation[] annotations,
        Retrofit retrofit) {
      if (getRawType(returnType) != Call.class) return null;
      final Type responseType = getParameterUpperBound(0, (ParameterizedType) returnType);
      return new CallAdapter<Object, Call<?>>() {
        @Override public Type responseType() {
          return responseType;
        }

        @Override public Call<?> adapt(Call<Object> call) {
          delegateAdaptCalled.set(true);
          return call;
        }
      };
    }
  }
}

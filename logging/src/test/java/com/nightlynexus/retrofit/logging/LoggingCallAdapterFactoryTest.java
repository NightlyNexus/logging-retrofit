package com.nightlynexus.retrofit.logging;

import java.io.IOException;
import java.lang.annotation.Annotation;
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
import retrofit2.http.Path;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

@RunWith(JUnit4.class)
public final class LoggingCallAdapterFactoryTest {
  @Test public void errorMessageHelper() throws IOException {
    ResponseBody errorBody = ResponseBody.create("This request failed.", null);
    BufferedSource source = errorBody.source();
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody)).isEqualTo("This request failed.");
    assertThat(source.exhausted()).isTrue();
  }

  @Test public void errorMessageHelperReturnsEmptyStringForEmptyBody() throws IOException {
    ResponseBody errorBody = ResponseBody.create(new byte[0], null);
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody)).isEmpty();
  }

  @Test public void errorMessageHelperChecksForPlainText() throws IOException {
    ResponseBody errorBody = ResponseBody.create(String.valueOf((char) 0x9F), null);
    assertThat(LoggingCallAdapterFactory.errorMessage(errorBody)).isNull();
  }

  interface Service {
    @GET("/") Call<String> getString();

    @GET("/") TestCall getTestString();

    @GET("/{a}") Call<Void> getWithPath(@Path("a") Object a);
  }

  @Test public void cannotConsumeErrorBody() throws IOException {
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder().baseUrl(server.url("/"))
        .addCallAdapterFactory(
            new LoggingCallAdapterFactory(new LoggingCallAdapterFactory.Logger() {
              @Override public <T> void onResponse(Call<T> call, Response<T> response) {
                try {
                  assertThat(response.errorBody().source().readUtf8())
                      .isEqualTo("This request failed.");
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
    Response<String> response = service.getString().execute();
    assertThat(response.errorBody().source().readUtf8()).isEqualTo("This request failed.");
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
      @Override
      public void onResponse(Call<String> call, Response<String> response) {
      }

      @Override
      public void onFailure(Call<String> call, Throwable t) {
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

  @Test public void executeLogsOnFailure() {
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
            return (Converter<ResponseBody, Object>) value -> {
              throw new RuntimeException("Broken!");
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
            return (Converter<ResponseBody, Object>) value -> {
              throw new RuntimeException("Broken!");
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
            return (Converter<ResponseBody, Object>) value -> {
              throw new OutOfMemoryError("Broken!");
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
    Call<Void> call = service.getWithPath(a);
    call.enqueue(new Callback<Void>() {
      @Override
      public void onResponse(Call<Void> call, Response<Void> response) {
        throw new AssertionError();
      }

      @Override
      public void onFailure(Call<Void> call, Throwable t) {
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
    Call<Void> call = service.getWithPath(a);
    try {
      call.execute();
      throw new AssertionError();
    } catch (RuntimeException ignored) {
    }
    assertThat(failureRef.get()).isInstanceOf(RuntimeException.class);
    assertThat(failureRef.get()).hasMessageThat().isEqualTo("Broken!");
  }

  @Test public void enqueueDoesNotLogRequestCreationFailureFatal() {
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
    Call<Void> call = service.getWithPath(a);
    try {
      call.enqueue(new Callback<Void>() {
        @Override
        public void onResponse(Call<Void> call, Response<Void> response) {
          throw new AssertionError();
        }

        @Override
        public void onFailure(Call<Void> call, Throwable t) {
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
    Call<Void> call = service.getWithPath(a);
    try {
      call.execute();
      throw new AssertionError();
    } catch (OutOfMemoryError ignored) {
    }
    assertThat(failureRef.get()).isNull();
  }

  @Test public void delegatesCallAdapter() throws IOException {
    MockWebServer server = new MockWebServer();
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
        .addCallAdapterFactory(new TestCall.AdapterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    // We would get a ClassCastException if the CallAdapter did not delegate.
    service.getTestString();
  }

  static final class TestCall {
    static final class AdapterFactory extends CallAdapter.Factory {
      @Override public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations,
          Retrofit retrofit) {
        if (getRawType(returnType) != TestCall.class) return null;
        return new CallAdapter<Object, TestCall>() {
          @Override public Type responseType() {
            return Void.class;
          }

          @Override public TestCall adapt(Call<Object> call) {
            return new TestCall();
          }
        };
      }
    }
  }
}

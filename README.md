Logging-Retrofit
====================

A Retrofit CallAdapter.Factory for transparent logging.


Download
--------

Download [the latest JAR][jar] or grab via Maven:
```xml
<dependency>
  <groupId>com.nightlynexus.logging-retrofit</groupId>
  <artifactId>logging</artifactId>
  <version>0.11.0</version>
</dependency>
```
or Gradle:
```groovy
implementation 'com.nightlynexus.logging-retrofit:logging:0.11.0'
```

TODO: Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].



Usage
-----

```java
LoggingCallAdapterFactory.Logger logger = new LoggingCallAdapterFactory.Logger() {
  @Override public <T> void onResponse(Call<T> call, Response<T> response) {
    // Implement.
  }

  @Override public <T> void onFailure(Call<T> call, Throwable t) {
    // Implement.
  }
};
Retrofit retrofit = new Retrofit.Builder()
    // Add the LoggingCallAdapterFactory before other CallAdapters factories
    // to let it delegate and log all types of calls.
    .addCallAdapterFactory(new LoggingCallAdapterFactory(logger))
    ...
    .build();
```


License
-------

    Copyright 2017 Eric Cochran

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.



 [jar]: https://search.maven.org/remote_content?g=com.nightlynexus.logging-retrofit&a=logging&v=LATEST
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/

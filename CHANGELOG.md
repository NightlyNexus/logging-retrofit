Change Log
==========

Version 0.11.0 *(2022-09-22)*
----------------------------

- Allow Loggers to consume the error body that is given to them. The error response given to the logger now has a peeked error body source.
- The errorMessage helper method now consumes its ResponseBody input.

Version 0.10.0 *(2018-11-18)*
----------------------------

Do not log fatal errors. This comes with the update to Retrofit 2.5.0 which will throw fatal errors (like `OutOfMemoryError`) from request creation instead of passing them into callbacks.

Version 0.9.0 *(2017-11-29)*
----------------------------

Initial release.

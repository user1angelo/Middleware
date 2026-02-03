# SDK Gaps and Future Improvements

## HTTP Listener Support
**Current State**: The `SuricataHttpModule` implements its own `com.sun.net.httpserver.HttpServer`.
**Gap**: The SDK (`nis-thesis-sdk`) does not provide a standardized way for modules to expose HTTP endpoints or Webhooks.
**Recommendation**: Add an `HttpListener` interface or utility in the SDK to allow modules to register routes (e.g., `api.registerWebHook("/suricata", handler)`) instead of managing their own servers and ports. This would prevent port conflicts.

## JSON Parsing
**Current State**: Modules use `com.google.gson` or `org.json` individually.
**Gap**: No standardized JSON utility in the SDK.
**Recommendation**: Expose a shared `JsonUtils` or `ObjectMapper` in the SDK to ensure consistent serialization/deserialization across all modules and reduce dependencies.

## Configuration Management
**Current State**: Modules manually load `.properties` files.
**Gap**: `CoreSystemApi` does not automatically load module config.
**Recommendation**: Add `api.getConfig("property.name")` to abstract file I/O and allow central configuration management.

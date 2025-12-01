# Jetty 9.4 → Jetty 12.1 Migration Plan - Enhanced

## Status: Ready to Execute

This plan has been reviewed and enhanced based on lessons learned from the Netty 3→4 migration.

---

## Phase 0: Pre-Migration Assessment ✅

### Version Compatibility Check
- ✅ Jetty 12.1.x is stable (latest LTS for Jakarta EE 10)
- ✅ Compatible with Java 17+ (your current version)
- ⚠️ **Requires Jakarta EE 10** (javax.servlet → jakarta.servlet)
- ⚠️ **Requires Jersey 3.x** (Jersey 1.x → 3.x is a MAJOR upgrade)

### Dependency Readiness Check Needed:
```bash
# Check which dependencies still use javax.servlet
mvn dependency:tree | grep javax.servlet

# Check for Jakarta compatibility
# pac4j: Version 5.x supports Jakarta ✅
# ranger: Check if available with Jakarta
# kerberos: May need updates
```

---

## Enhanced Migration Steps

### Phase 1: Dependency Updates (Week 1)

#### 1.1 Root POM Updates
```xml
<properties>
    <jetty.version>12.1.6</jetty.version>
    <servlet.version>6.0.0</servlet.version>
    <jaxrs.version>3.1.0</jaxrs.version>
    <jersey.version>3.1.5</jersey.version>
</properties>

<dependencyManagement>
    <!-- Jetty 12 BOM -->
    <dependency>
        <groupId>org.eclipse.jetty</groupId>
        <artifactId>jetty-bom</artifactId>
        <version>${jetty.version}</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
    
    <!-- Jakarta Servlet API -->
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
        <version>${servlet.version}</version>
    </dependency>
    
    <!-- Jakarta JAX-RS API -->
    <dependency>
        <groupId>jakarta.ws.rs</groupId>
        <artifactId>jakarta.ws.rs-api</artifactId>
        <version>${jaxrs.version}</version>
    </dependency>
</dependencyManagement>
```

**Action Items:**
- [ ] Update `pom.xml` with versions above
- [ ] Remove old `javax.servlet-api` dependency
- [ ] Add Jetty EE10 dependencies
- [ ] Test: `mvn dependency:tree` - verify no javax.servlet remains

#### 1.2 Module-Specific Dependencies
Update each module's `pom.xml`:
- [ ] server: Add `jetty-ee10-servlet`, `jetty-ee10-webapp`
- [ ] services: Update Jetty dependencies
- [ ] All modules: Replace `javax.servlet-api` → `jakarta.servlet-api`

**Critical: Avoid Mixed Dependencies**
- Exclude `javax.servlet` from ALL transitive dependencies
- Use `<exclusions>` aggressively

---

### Phase 2: Code Migration - Namespace Changes (Week 1-2)

#### 2.1 Automated Replacements (Similar to Netty Migration)

**Batch replace imports:**
```bash
# Servlet API
find . -path "*/src/main/**/*.java" -exec sed -i \
    's/import javax\.servlet\./import jakarta.servlet./g' {} \;

# JAX-RS API  
find . -path "*/src/main/**/*.java" -exec sed -i \
    's/import javax\.ws\.rs\./import jakarta.ws.rs./g' {} \;

# Validation API (if used)
find . -path "*/src/main/**/*.java" -exec sed -i \
    's/import javax\.validation\./import jakarta.validation./g' {} \;
```

**Action Items:**
- [ ] Run batch replacement scripts
- [ ] Compile each module: `mvn compile -pl <module> -DskipTests`
- [ ] Fix any compilation errors
- [ ] **CRITICAL:** Test files need the same replacements!

#### 2.2 Jetty-Specific Imports

**Package migrations:**
```
org.eclipse.jetty.servlet.* → org.eclipse.jetty.ee10.servlet.*
org.eclipse.jetty.servlets.* → org.eclipse.jetty.ee10.servlets.*
org.eclipse.jetty.proxy.* → org.eclipse.jetty.ee10.proxy.*
org.eclipse.jetty.rewrite.* → org.eclipse.jetty.ee10.rewrite.*
org.eclipse.jetty.webapp.* → org.eclipse.jetty.ee10.webapp.*
```

**Core Jetty classes stay the same:**
```
org.eclipse.jetty.server.Server ✓ (no change)
org.eclipse.jetty.server.Connector ✓
org.eclipse.jetty.util.* ✓
org.eclipse.jetty.io.* ✓
```

**Action Items:**
- [ ] Update servlet handler imports in `JettyServerModule.java`
- [ ] Update proxy imports in forwarding servlets
- [ ] Update QoS imports
- [ ] Compile and test each change

---

### Phase 3: API Changes (Week 2)

#### 3.1 Jetty 12 API Differences

**Known Breaking Changes:**

1. **ServletContextHandler Constructor:**
```java
// Jetty 9:
ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

// Jetty 12:
ServletContextHandler context = ServletContextHandler.ee10();
context.setSessionHandler(new SessionHandler());
```

2. **SslContextFactory Split:**
```java
// Jetty 9:
SslContextFactory sslContextFactory = new SslContextFactory();

// Jetty 12:
SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
// or
SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
```

3. **Resource Handling:**
```java
// Jetty 9:
context.setResourceBase("/path");

// Jetty 12:
context.setBaseResource(ResourceFactory.root().newResource("/path"));
```

4. **Request/Response API:**
```java
// Some methods renamed or moved
request.getContentType() // Still works
response.setContentType() // Still works  
// But internal APIs may have changed
```

**Action Items:**
- [ ] Update `JettyServerModule.java` for Jetty 12 APIs
- [ ] Update `TLSServerConfig.java` for SslContextFactory.Server
- [ ] Test TLS configuration thoroughly
- [ ] Update all `ServletContextHandler` instantiations

#### 3.2 Jersey 1 → Jersey 3 Migration (MAJOR)

This is the BIGGEST challenge. Jersey 1 uses different APIs entirely.

**Current (Jersey 1):**
```java
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.spi.container.servlet.ServletContainer;
```

**Target (Jersey 3):**
```java
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.server.ResourceConfig;
// Guice integration needs Jersey-HK2 bridge
```

**Jersey-Guice Bridge:**
```xml
<dependency>
    <groupId>org.glassfish.hk2</groupId>
    <artifactId>guice-bridge</artifactId>
</dependency>
```

**Action Items:**
- [ ] Replace `JerseyServletModule` with custom Guice module using Jersey 3
- [ ] Set up HK2-Guice bridge for dependency injection
- [ ] Update all `@Provider` classes (exception mappers, filters)
- [ ] Test REST endpoint registration and injection
- [ ] **HIGH RISK:** This could break all REST APIs if done incorrectly

---

### Phase 4: Testing Strategy (Week 3)

#### 4.1 Unit Tests
- [ ] Update test imports (javax → jakarta)
- [ ] Fix test mocks (`MockHttpServletRequest`, etc.) for Jakarta
- [ ] Run: `mvn test -pl server` - fix failures iteratively
- [ ] Run: `mvn test -pl services` - verify CLI entry points work

#### 4.2 Integration Tests
- [ ] Update integration test servlet/JAX-RS code
- [ ] Run current integration test suite
- [ ] **Add to integration test** (similar to Netty validation):
  ```bash
  # Verify Jetty endpoints work
  curl http://localhost:8081/status/health
  curl http://localhost:8082/druid/v2/datasources
  # Test proxy forwarding through router
  curl http://localhost:8888/druid/coordinator/v1/leader
  ```

#### 4.3 Specific Tests for Jetty Features
- [ ] TLS/HTTPS connections
- [ ] Certificate reload (KeyStoreScanner)
- [ ] QoS throttling
- [ ] Gzip compression
- [ ] Async servlet forwarding
- [ ] Request logging
- [ ] HSTS headers

---

### Phase 5: Build & Deployment (Week 3-4)

#### 5.1 Build Configuration
Update Dockerfile similar to Netty migration:
```dockerfile
# Ensure Jakarta dependencies are used
RUN mvn -B -q versions:update-property \
    -Dproperty=jetty.version -DnewVersion=12.1.6 \
    && mvn -B -q versions:update-property \
    -Dproperty=servlet.version -DnewVersion=6.0.0
```

#### 5.2 Memory Considerations
Jetty 12 may have different memory profile:
- Monitor thread pool sizes
- Check direct memory usage (similar to Netty issue)
- May need to adjust `MaxDirectMemorySize`

#### 5.3 Deployment Checklist
- [ ] Build with `-Dmaven.test.skip=true` first (verify compilation)
- [ ] Run integration tests
- [ ] Deploy to test environment
- [ ] Verify all services start
- [ ] Test queries, ingestion, compaction
- [ ] Rolling restart test
- [ ] Monitor for 24h before production

---

## Risk Assessment & Mitigation

### High Risk Items

#### 1. Jersey 1 → 3 Migration (HIGHEST RISK)
**Impact:** Could break ALL REST APIs  
**Mitigation:**
- Create Jersey 3 integration in separate branch first
- Test thoroughly before merging
- Have rollback plan ready
- Consider doing in separate PR after Jetty base migration

#### 2. Mixed javax/jakarta Dependencies
**Impact:** ClassLoader conflicts, ClassCastException at runtime  
**Mitigation:**
- Use `mvn dependency:tree` extensively
- Exclude javax.servlet from ALL dependencies
- Test with `-Dio.netty.leakDetection.level=PARANOID` equivalent for classloading

#### 3. Proxy Servlet Behavior Changes
**Impact:** Router forwarding may break  
**Mitigation:**
- Test proxy forwarding extensively
- Verify headers are forwarded correctly
- Check authentication passthrough

### Medium Risk Items

#### 4. TLS Configuration
**Impact:** HTTPS may not work, cert reload may fail  
**Mitigation:**
- Test TLS thoroughly in staging
- Verify KeyStoreScanner still works
- Test certificate rotation

#### 5. Third-Party Extensions
**Impact:** Kerberos, pac4j, ranger may not have Jakarta versions  
**Mitigation:**
- Check version availability BEFORE starting migration
- May need to temporarily disable some extensions
- Document which extensions are incompatible

---

## Lessons from Netty Migration Applied

### What Worked Well:
✅ Batch sed replacements for simple namespace changes  
✅ Module-by-module compilation  
✅ Integration tests to validate end-to-end  
✅ Incremental commits  
✅ Good documentation  

### What to Improve:
⚠️ **Start with dependency check** - verify all deps have Jakarta versions  
⚠️ **Test early and often** - don't wait until end  
⚠️ **Watch for API behavior changes** - not just imports  
⚠️ **Memory/performance testing** - catch resource issues early  
⚠️ **Have rollback plan** - Jersey migration could go wrong  

---

## Recommended Phasing

### Option A: Big Bang (Faster, Higher Risk)
Do Jetty 12 + Jakarta + Jersey 3 all at once.
- **Pros:** Done in one go
- **Cons:** Hard to debug, large blast radius

### Option B: Staged (Recommended)
1. **First:** Jetty 12 base + Jakarta servlet (keep Jersey 1 somehow)
2. **Then:** Jersey 3 migration
- **Pros:** Easier to debug, smaller changes
- **Cons:** May not be possible (Jersey 1 might not work with Jakarta)

### Option C: Hybrid
1. Jetty 12 + Jakarta servlet + Jersey 3 (REST APIs)
2. Keep some javax for extensions temporarily
3. Migrate extensions later
- **Pros:** Core works, extensions can lag
- **Cons:** Mixed dependencies risky

**Recommendation:** Try **Option B** if possible, fall back to **Option A** if Jersey 1 is incompatible with Jakarta.

---

## Critical Additions to Plan

### Missing Items to Add:

1. **Jakarta Annotations:**
```bash
# Also need to replace:
javax.annotation.* → jakarta.annotation.*
javax.inject.* → jakarta.inject.* (if used)
```

2. **Jackson JAX-RS Providers:**
Update to Jakarta-compatible versions:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.jakarta.rs</groupId>
    <artifactId>jackson-jakarta-rs-json-provider</artifactId>
</dependency>
```

3. **Guice-Jersey Integration:**
This is NEW territory - need to research:
- Jersey 3 + Guice integration options
- HK2 bridge setup
- May need custom integration layer

4. **Web Console Implications:**
- Check if web-console depends on specific JAX-RS client versions
- May need to update frontend API calls

5. **Error Handling:**
- Exception mappers need to be re-registered with Jersey 3
- Verify error responses still work

---

## Pre-Migration Checklist

Before starting, verify:
- [ ] All critical dependencies have Jakarta-compatible versions
- [ ] Jersey 3 + Guice integration is feasible
- [ ] Extensions (pac4j, kerberos, ranger) have Jakarta versions
- [ ] Have tested Jetty 12 in a minimal app first
- [ ] Have rollback plan (can revert to Jetty 9.4)

**STOP CONDITION:** If pac4j, kerberos, or ranger don't have Jakarta versions, **DO NOT PROCEED** until they do, or plan to disable those extensions.

---

## Estimated Timeline

- **Phase 1 (Dependencies):** 2-3 days
- **Phase 2 (Namespace migration):** 3-5 days  
- **Phase 3 (API changes + Jersey):** 5-10 days (Jersey 3 is complex)
- **Phase 4 (Testing):** 5-7 days
- **Phase 5 (Deployment):** 2-3 days
- **Buffer:** 5 days

**Total:** 4-6 weeks

**Critical Path:** Jersey 1 → 3 migration (most complex)

---

## Decision Point

**BEFORE PROCEEDING:**

Check dependency availability:
```bash
# 1. Check pac4j Jakarta support
# Version 5.x supports Jakarta EE

# 2. Check ranger
mvn versions:display-dependency-updates | grep ranger

# 3. Check kerberos dependencies
mvn dependency:tree -pl extensions-core/druid-kerberos | grep javax
```

If any critical dependency is stuck on javax, you have two options:
1. **Wait** for Jakarta versions
2. **Disable** those extensions for the migration

---

## Recommendation

**Given the complexity and risks, I recommend:**

1. ✅ **Plan is good** - comprehensive and well-thought-out
2. ⚠️ **Add dependency verification step** BEFORE starting code changes
3. ⚠️ **Consider Jersey 3 migration separately** - it's a huge undertaking
4. ✅ **Reuse patterns from Netty migration** - batch replacements, incremental testing
5. ⚠️ **Expect 4-6 weeks** - don't rush it

**Ready to proceed?** 

First action: Run dependency checks to verify all required libraries have Jakarta versions. Share the results and we'll confirm go/no-go.


package org.folio.okapi.managers;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.bean.InstallJob;
import org.folio.okapi.bean.InterfaceDescriptor;
import org.folio.okapi.bean.ModuleDescriptor;
import org.folio.okapi.bean.ModuleInstance;
import org.folio.okapi.bean.PermissionList;
import org.folio.okapi.bean.RoutingEntry;
import org.folio.okapi.bean.Tenant;
import org.folio.okapi.bean.TenantDescriptor;
import org.folio.okapi.bean.TenantModuleDescriptor;
import org.folio.okapi.bean.TenantModuleDescriptor.Action;
import org.folio.okapi.common.ErrorType;
import org.folio.okapi.common.Messages;
import org.folio.okapi.common.ModuleId;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.service.Liveness;
import org.folio.okapi.service.TenantStore;
import org.folio.okapi.util.DepResolution;
import org.folio.okapi.util.LockedTypedMap1;
import org.folio.okapi.util.LockedTypedMap2;
import org.folio.okapi.util.ModuleCache;
import org.folio.okapi.util.OkapiError;
import org.folio.okapi.util.ProxyContext;
import org.folio.okapi.util.TenantInstallOptions;

/**
 * Manages the tenants in the shared map, and passes updates to the database.
 */
@java.lang.SuppressWarnings({"squid:S1192"}) // String literals should not be duplicated
public class TenantManager implements Liveness {

  private final Logger logger = OkapiLogger.get();
  private ModuleManager moduleManager;
  private ProxyService proxyService = null;
  private DiscoveryManager discoveryManager;
  private final TenantStore tenantStore;
  private LockedTypedMap1<Tenant> tenants = new LockedTypedMap1<>(Tenant.class);
  private String mapName = "tenants";
  private LockedTypedMap2<InstallJob> jobs = new LockedTypedMap2<>(InstallJob.class);
  private static final String EVENT_NAME = "timer";
  private Set<String> timers = new HashSet<>();
  private Messages messages = Messages.getInstance();
  private Vertx vertx;
  private Map<String, ModuleCache> enabledModulesCache = new HashMap<>();
  // tenants with new permission module (_tenantPermissions version 1.1 or later)
  private Map<String, Boolean> expandedModulesCache = new HashMap<>();
  private final boolean local;

  /**
   * Create tenant manager.
   *
   * @param moduleManager module manager
   * @param tenantStore   tenant storage
   */
  public TenantManager(ModuleManager moduleManager, TenantStore tenantStore, boolean local) {
    this.moduleManager = moduleManager;
    this.tenantStore = tenantStore;
    this.local = local;
  }

  void setTenantsMap(LockedTypedMap1<Tenant> tenants) {
    this.tenants = tenants;
  }

  /**
   * Initialize the TenantManager.
   *
   * @param vertx Vert.x handle
   * @return fut future
   */
  public Future<Void> init(Vertx vertx) {
    this.vertx = vertx;

    return tenants.init(vertx, mapName, local)
        .compose(x -> jobs.init(vertx, "installJobs", local))
        .compose(x -> loadTenants());
  }

  /**
   * Set the proxyService. So that we can use it to call the tenant interface,
   * etc.
   *
   * @param px Proxy Service handle
   */
  public void setProxyService(ProxyService px) {
    this.proxyService = px;
  }

  /**
   * Insert a tenant.
   *
   * @param t tenant
   * @return future
   */
  public Future<String> insert(Tenant t) {
    String id = t.getId();
    return tenants.get(id)
        .compose(gres -> {
          if (gres != null) { // already exists
            return Future.failedFuture(new OkapiError(ErrorType.USER,
                messages.getMessage("10400", id)));
          }
          return Future.succeededFuture();
        })
        .compose(res1 -> tenantStore.insert(t))
        .compose(res2 -> tenants.add(id, t))
        .compose(x -> Future.succeededFuture(id));
  }

  Future<Void> updateDescriptor(TenantDescriptor td) {
    final String id = td.getId();
    return tenants.get(id).compose(gres -> {
      Tenant t;
      if (gres != null) {
        t = new Tenant(td, gres.getEnabled());
      } else {
        t = new Tenant(td);
      }
      return tenantStore.updateDescriptor(td).compose(res -> tenants.add(id, t));
    });
  }

  Future<List<TenantDescriptor>> list() {
    return tenants.getKeys().compose(lres -> {
      List<Future> futures = new LinkedList<>();
      List<TenantDescriptor> tdl = new LinkedList<>();
      for (String s : lres) {
        futures.add(tenants.getNotFound(s).compose(res -> {
          tdl.add(res.getDescriptor());
          return Future.succeededFuture();
        }));
      }
      return CompositeFuture.all(futures).map(tdl);
    });
  }

  /**
   * Get a tenant.
   *
   * @param tenantId tenant ID
   * @return fut future
   */
  public Future<Tenant> get(String tenantId) {
    return tenants.getNotFound(tenantId);
  }

  /**
   * Delete a tenant.
   *
   * @param tenantId tenant ID
   * @return future .. OkapiError if tenantId not found
   */
  public Future<Void> delete(String tenantId) {
    return tenantStore.delete(tenantId).compose(x -> {
      if (Boolean.FALSE.equals(x)) {
        return Future.failedFuture(new OkapiError(ErrorType.NOT_FOUND, tenantId));
      }
      return tenants.removeNotFound(tenantId).mapEmpty();
    }).compose(x -> reloadEnabledModules(tenantId));
  }

  /**
   * Update module for tenant and commit to storage.
   * @param t tenant
   * @param moduleFrom null if no original module
   * @param moduleTo null if removing a module for tenant
   * @return fut async result
   */
  public Future<Void> updateModuleCommit(Tenant t, String moduleFrom, String moduleTo) {
    String id = t.getId();
    if (moduleFrom != null) {
      t.disableModule(moduleFrom);
    }
    if (moduleTo != null) {
      t.enableModule(moduleTo);
    }
    return tenantStore.updateModules(id, t.getEnabled()).compose(ures -> {
      if (Boolean.FALSE.equals(ures)) {
        return Future.failedFuture(new OkapiError(ErrorType.NOT_FOUND, id));
      }
      return tenants.put(id, t);
    }).compose(x -> reloadEnabledModules(t));
  }

  Future<Void> disableModules(String tenantId, TenantInstallOptions options, ProxyContext pc) {
    options.setDepCheck(false);
    return listModules(tenantId).compose(res -> {
      Future<Void> future = Future.succeededFuture();
      for (ModuleDescriptor md : res) {
        future = future.compose(x -> enableAndDisableModule(tenantId, options,
            md.getId(), null, pc).mapEmpty());
      }
      return future;
    });
  }

  Future<Void> enableAndDisableCheck(Tenant tenant, ModuleDescriptor modFrom,
                                     ModuleDescriptor modTo) {

    return getEnabledModules(tenant).compose(modlist -> {
      HashMap<String, ModuleDescriptor> mods = new HashMap<>(modlist.size());
      for (ModuleDescriptor md : modlist) {
        mods.put(md.getId(), md);
      }
      if (modTo == null) {
        String deps = DepResolution.checkAllDependencies(mods);
        if (!deps.isEmpty()) {
          return Future.succeededFuture(); // failures even before we remove a module
        }
      }
      if (modFrom != null) {
        mods.remove(modFrom.getId());
      }
      if (modTo != null) {
        ModuleDescriptor already = mods.get(modTo.getId());
        if (already != null) {
          return Future.failedFuture(new OkapiError(ErrorType.USER,
              "Module " + modTo.getId() + " already provided"));
        }
        mods.put(modTo.getId(), modTo);
      }
      String conflicts = DepResolution.checkAllConflicts(mods);
      String deps = DepResolution.checkAllDependencies(mods);
      if (!conflicts.isEmpty() || !deps.isEmpty()) {
        return Future.failedFuture(new OkapiError(ErrorType.USER, conflicts + " " + deps));
      }
      return Future.succeededFuture();
    });
  }

  Future<String> enableAndDisableModule(
      String tenantId, TenantInstallOptions options, String moduleFrom,
      TenantModuleDescriptor td, ProxyContext pc) {

    return tenants.getNotFound(tenantId).compose(tenant ->
        enableAndDisableModule(tenant, options, moduleFrom, td != null ? td.getId() : null, pc));
  }

  private Future<String> enableAndDisableModule(
      Tenant tenant, TenantInstallOptions options, String moduleFrom,
      String moduleTo, ProxyContext pc) {

    Future<ModuleDescriptor> mdFrom = moduleFrom != null
        ? moduleManager.get(moduleFrom) : Future.succeededFuture(null);
    Future<ModuleDescriptor> mdTo = moduleTo != null
        ? moduleManager.getLatest(moduleTo) : Future.succeededFuture(null);
    return mdFrom
        .compose(x -> mdTo)
        .compose(x -> options.getDepCheck()
              ? enableAndDisableCheck(tenant, mdFrom.result(), mdTo.result())
              : Future.succeededFuture())
        .compose(x -> enableAndDisableModule(tenant, options, mdFrom.result(), mdTo.result(), pc));
  }

  private Future<String> enableAndDisableModule(Tenant tenant, TenantInstallOptions options,
                                                ModuleDescriptor mdFrom, ModuleDescriptor mdTo,
                                                ProxyContext pc) {
    if (mdFrom == null && mdTo == null) {
      return Future.succeededFuture("");
    }
    return invokePermissions(tenant, options, mdTo, pc)
        .compose(x -> invokeTenantInterface(tenant, options, mdFrom, mdTo, pc))
        .compose(x -> invokePermissionsPermMod(tenant, options, mdFrom, mdTo, pc))
        .compose(x -> commitModuleChange(tenant, mdFrom, mdTo, pc))
        .compose(x -> Future.succeededFuture((mdTo != null ? mdTo.getId() : ""))
    );
  }

  /**
   * invoke the tenant interface for a module.
   *
   * @param tenant tenant
   * @param mdFrom module from
   * @param mdTo module to
   * @return fut future
   */
  private Future<Void> invokeTenantInterface(Tenant tenant, TenantInstallOptions options,
                                             ModuleDescriptor mdFrom, ModuleDescriptor mdTo,
                                             ProxyContext pc) {

    if (!options.getInvoke()) {
      return Future.succeededFuture();
    }
    JsonObject jo = new JsonObject();
    if (mdTo != null) {
      jo.put("module_to", mdTo.getId());
    }
    if (mdFrom != null) {
      jo.put("module_from", mdFrom.getId());
    }
    String tenantParameters = options.getTenantParameters();
    boolean purge = mdTo == null && options.getPurge();
    return getTenantInstanceForModule(mdFrom, mdTo, jo, tenantParameters, purge)
        .compose(instance -> {
          if (instance == null) {
            logger.debug("{}: has no support for tenant init",
                (mdTo != null ? mdTo.getId() : mdFrom.getId()));
            return Future.succeededFuture();
          }
          final String req = purge ? "" : jo.encodePrettily();
          return proxyService.callSystemInterface(tenant, instance, req, pc).compose(cres -> {
            pc.passOkapiTraceHeaders(cres);
            // We can ignore the result, the call went well.
            return Future.succeededFuture();
          });
        });
  }

  /**
   * If enabling non-permissions module, announce permissions to permissions module if enabled.
   *
   * @param tenant tenant
   * @param options install options
   * @param mdTo module to
   * @param pc proxy content
   * @return Future
   */
  private Future<Void> invokePermissions(Tenant tenant, TenantInstallOptions options,
                                         ModuleDescriptor mdTo,
                                         ProxyContext pc) {
    if (mdTo == null || !options.getInvoke()
        || mdTo.getSystemInterface("_tenantPermissions") != null) {
      return Future.succeededFuture();
    }
    return findSystemInterface(tenant, "_tenantPermissions").compose(md -> {
      if (md == null) {
        return Future.succeededFuture();
      }
      return invokePermissionsForModule(tenant, mdTo, md, pc);
    });
  }

  /**
   * If enabling permissions module it, announce permissions to it.
   *
   * @param tenant tenant
   * @param options install options
   * @param mdFrom module from
   * @param mdTo module to
   * @param pc proxy context
   * @return fut response
   */
  private Future<Void> invokePermissionsPermMod(Tenant tenant, TenantInstallOptions options,
                                                ModuleDescriptor mdFrom, ModuleDescriptor mdTo,
                                                ProxyContext pc) {
    if (mdTo == null || !options.getInvoke()
        || mdTo.getSystemInterface("_tenantPermissions") == null) {
      return Future.succeededFuture();
    }
    // enabling permissions module.
    String moduleFrom = mdFrom != null ? mdFrom.getId() : null;
    return findSystemInterface(tenant, "_tenantPermissions")
        .compose(res -> {
          if (res == null) { // == null : no permissions module already enabled
            return loadPermissionsForEnabled(tenant, mdTo, pc);
          } else {
            return Future.succeededFuture();
          }
        })
        .compose(res -> invokePermissionsForModule(tenant, mdTo, mdTo, pc));
  }

  /**
   * Announce permissions for a set of modules to a permissions module.
   *
   * @param tenant tenant
   * @param permsModule permissions module
   * @param pc ProxyContext
   * @return future
   */
  private Future<Void> loadPermissionsForEnabled(
      Tenant tenant, ModuleDescriptor permsModule, ProxyContext pc) {

    Future<Void> future = Future.succeededFuture();
    for (String mdid : tenant.listModules()) {
      future = future.compose(x -> moduleManager.get(mdid)
          .compose(md -> invokePermissionsForModule(tenant, md, permsModule, pc)));
    }
    return future;
  }

  /**
   * Commit change of module for tenant and publish on event bus about it.
   *
   * @param tenant tenant
   * @param mdFrom module from (null if new module)
   * @param mdTo module to (null if module is removed)
   * @param pc ProxyContext
   * @return future
   */
  private Future<Void> commitModuleChange(Tenant tenant, ModuleDescriptor mdFrom,
                                          ModuleDescriptor mdTo, ProxyContext pc) {

    String moduleFrom = mdFrom != null ? mdFrom.getId() : null;
    String moduleTo = mdTo != null ? mdTo.getId() : null;

    Promise<Void> promise = Promise.promise();
    return updateModuleCommit(tenant, moduleFrom, moduleTo)
        .compose(x -> {
          if (moduleTo != null) {
            EventBus eb = vertx.eventBus();
            eb.publish(EVENT_NAME, tenant.getId());
          }
          return Future.succeededFuture();
        });
  }

  /**
   * start timers for all tenants.
   * @param discoveryManager discovery manager
   * @return async result
   */
  public Future<Void> startTimers(DiscoveryManager discoveryManager, String okapiVersion) {
    final ModuleDescriptor md = InternalModule.moduleDescriptor(okapiVersion);
    this.discoveryManager = discoveryManager;
    return tenants.getKeys().compose(res -> {
      for (String tenantId : res) {
        reloadEnabledModules(tenantId).onComplete(x -> handleTimer(tenantId));
      }
      Future<Void> future = Future.succeededFuture();
      for (String tenantId : res) {
        future = future.compose(x -> upgradeOkapiModule(tenantId, md));
      }
      consumeTimers();
      return future;
    });
  }

  private Future<Void> upgradeOkapiModule(String tenantId, ModuleDescriptor md) {
    return get(tenantId).compose(tenant -> {
      String moduleTo = md.getId();
      Set<String> enabledMods = tenant.getEnabled().keySet();
      String enver = null;
      for (String emod : enabledMods) {
        if (emod.startsWith("okapi-")) {
          enver = emod;
        }
      }
      String moduleFrom = enver;
      if (moduleFrom == null) {
        logger.info("Tenant {} does not have okapi module enabled already", tenantId);
        return Future.succeededFuture();
      }
      if (moduleFrom.equals(moduleTo)) {
        logger.info("Tenant {} has module {} enabled already", tenantId, moduleTo);
        return Future.succeededFuture();
      }
      if (ModuleId.compare(moduleFrom, moduleTo) >= 4) {
        logger.warn("Will not downgrade tenant {} from {} to {}", tenantId, moduleTo, moduleFrom);
        return Future.succeededFuture();
      }
      logger.info("Tenant {} moving from {} to {}", tenantId, moduleFrom, moduleTo);
      TenantInstallOptions options = new TenantInstallOptions();
      return invokePermissions(tenant, options, md, null).compose(x ->
          updateModuleCommit(tenant, moduleFrom, moduleTo));
    });
  }

  /**
   * For unit testing.
   */
  Set<String> getTimers() {
    return timers;
  }

  private void consumeTimers() {
    EventBus eb = vertx.eventBus();
    eb.consumer(EVENT_NAME, res -> {
      String tenantId = (String) res.body();
      reloadEnabledModules(tenantId).onComplete(x -> handleTimer(tenantId));
    });
  }

  private void stopTimer(String tenantId, String moduleId, int seq) {
    logger.info("remove timer for module {} for tenant {}", moduleId, tenantId);
    final String key = tenantId + "_" + moduleId + "_" + seq;
    timers.remove(key);
  }

  private void handleTimer(String tenantId) {
    handleTimer(tenantId, null, 0);
  }

  void handleTimer(String tenantId, String moduleId, int seq1) {
    logger.info("handleTimer tenant {} module {} seq1 {}", tenantId, moduleId, seq1);
    tenants.getNotFound(tenantId).onFailure(cause ->
        stopTimer(tenantId, moduleId, seq1)
    ).onSuccess(tenant -> {
      getEnabledModules(tenant).onFailure(cause ->
          stopTimer(tenantId, moduleId, seq1)
      ).onSuccess(mdList -> {
        try {
          handleTimer(tenant, mdList, moduleId, seq1);
        } catch (Exception ex) {
          logger.warn("handleTimer exception {}", ex.getMessage(), ex);
        }
      });
    });
  }

  private void handleTimer(Tenant tenant, List<ModuleDescriptor> mdList,
                           String moduleId, int seq1) {
    int noTimers = 0;
    final String tenantId = tenant.getId();
    for (ModuleDescriptor md : mdList) {
      if (moduleId == null || moduleId.equals(md.getId())) {
        InterfaceDescriptor timerInt = md.getSystemInterface("_timer");
        if (timerInt != null) {
          List<RoutingEntry> routingEntries = timerInt.getAllRoutingEntries();
          noTimers += handleTimer(tenant, md, routingEntries, seq1);
        }
      }
    }
    if (noTimers == 0) {
      // module no longer enabled for tenant
      stopTimer(tenantId, moduleId, seq1);
    }
    logger.info("handleTimer done no {}", noTimers);
  }

  private int handleTimer(Tenant tenant, ModuleDescriptor md,
                          List<RoutingEntry> routingEntries, int seq1) {
    int i = 0;
    final String tenantId = tenant.getId();
    for (RoutingEntry re : routingEntries) {
      final int seq = ++i;
      final String key = tenantId + "_" + md.getId() + "_" + seq;
      final long delay = re.getDelayMilliSeconds();
      final String path = re.getStaticPath();
      if (delay > 0 && path != null) {
        if (seq1 == 0) {
          if (!timers.contains(key)) {
            timers.add(key);
            waitTimer(tenantId, md, delay, seq);
          }
        } else if (seq == seq1) {
          if (discoveryManager.isLeader()) {
            fireTimer(tenant, md, re, path);
          }
          waitTimer(tenantId, md, delay, seq);
          return 1;
        }
      }
    }
    return 0;
  }

  private void waitTimer(String tenantId, ModuleDescriptor md, long delay, int seq) {
    vertx.setTimer(delay, res
        -> handleTimer(tenantId, md.getId(), seq));
  }

  private void fireTimer(Tenant tenant, ModuleDescriptor md, RoutingEntry re, String path) {
    String tenantId = tenant.getId();
    HttpMethod httpMethod = re.getDefaultMethod(HttpMethod.POST);
    ModuleInstance inst = new ModuleInstance(md, re, path, httpMethod, true);
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    logger.info("timer call start module {} for tenant {}", md.getId(), tenantId);
    proxyService.callSystemInterface(headers, tenant, inst, "").onFailure(cause ->
        logger.info("timer call failed to module {} for tenant {} : {}",
            md.getId(), tenantId, cause.getMessage())
    ).onSuccess(res ->
        logger.info("timer call succeeded to module {} for tenant {}",
            md.getId(), tenantId)
    );
  }

  private Future<Void> invokePermissionsForModule(Tenant tenant, ModuleDescriptor mdTo,
                                                  ModuleDescriptor permsModule, ProxyContext pc) {

    logger.debug("Loading permissions for {} (using {})", mdTo.getName(), permsModule.getName());
    String moduleTo = mdTo.getId();
    PermissionList pl = null;
    InterfaceDescriptor permInt = permsModule.getSystemInterface("_tenantPermissions");
    if (permInt.getVersion().equals("1.0")) {
      pl = new PermissionList(moduleTo, mdTo.getPermissionSets());
    } else {
      pl = new PermissionList(moduleTo, mdTo.getExpandedPermissionSets());
    }
    String pljson = Json.encodePrettily(pl);
    logger.debug("tenantPerms Req: {}", pljson);
    String permPath = "";
    List<RoutingEntry> routingEntries = permInt.getAllRoutingEntries();
    ModuleInstance permInst = null;
    if (!routingEntries.isEmpty()) {
      for (RoutingEntry re : routingEntries) {
        if (re.match(null, "POST")) {
          permPath = re.getStaticPath();
          permInst = new ModuleInstance(permsModule, re, permPath, HttpMethod.POST, true);
        }
      }
    }
    if (permInst == null) {
      return Future.failedFuture(new OkapiError(ErrorType.USER,
          "Bad _tenantPermissions interface in module " + permsModule.getId()
              + ". No path to POST to"));
    }
    logger.debug("tenantPerms: {} and {}", permsModule.getId(), permPath);
    if (pc == null) {
      MultiMap headersIn = MultiMap.caseInsensitiveMultiMap();
      return proxyService.doCallSystemInterface(headersIn, tenant.getId(), null,
          permInst, null, pljson).mapEmpty();
    }
    return proxyService.callSystemInterface(tenant, permInst, pljson, pc).compose(cres -> {
      pc.passOkapiTraceHeaders(cres);
      logger.debug("tenantPerms request to {} succeeded for module {} and tenant {}",
          permsModule.getId(), moduleTo, tenant.getId());
      return Future.succeededFuture();
    });
  }

  /**
   * Find the tenant API interface. Supports several deprecated versions of the
   * tenant interface: the 'tenantInterface' field in MD; if the module provides
   * a '_tenant' interface without RoutingEntries, and finally the proper way,
   * if the module provides a '_tenant' interface that is marked as a system
   * interface, and has a RoutingEntry that supports POST.
   *
   * @param mdFrom module from
   * @param mdTo module to
   * @param jo Json Object to be POSTed
   * @param tenantParameters tenant parameters (eg sample data)
   * @param purge true if purging (DELETE)
   * @return future (result==null if no tenant interface!)
   */
  private Future<ModuleInstance> getTenantInstanceForModule(
      ModuleDescriptor mdFrom,
      ModuleDescriptor mdTo, JsonObject jo, String tenantParameters, boolean purge) {

    ModuleDescriptor md = mdTo != null ? mdTo : mdFrom;
    InterfaceDescriptor[] prov = md.getProvidesList();
    for (InterfaceDescriptor pi : prov) {
      logger.debug("findTenantInterface: Looking at {}", pi.getId());
      if ("_tenant".equals(pi.getId())) {
        final String v = pi.getVersion();
        final String method = purge ? "DELETE" : "POST";
        ModuleInstance instance;
        switch (v) {
          case "1.0":
            if (mdTo != null || purge) {
              instance = getTenantInstanceForInterface(pi, mdFrom, mdTo, method);
              if (instance != null) {
                return Future.succeededFuture(instance);
              } else if (!purge) {
                logger.warn("Module '{}' uses old-fashioned tenant "
                    + "interface. Define InterfaceType=system, and add a RoutingEntry."
                    + " Falling back to calling /_/tenant.", md.getId());
                return Future.succeededFuture(new ModuleInstance(md, null,
                    "/_/tenant", HttpMethod.POST, true).withRetry());
              }
            }
            break;
          case "1.1":
            instance = getTenantInstanceForInterface(pi, mdFrom, mdTo, method);
            if (instance != null) {
              return Future.succeededFuture(instance);
            }
            break;
          case "1.2":
            putTenantParameters(jo, tenantParameters);
            instance = getTenantInstanceForInterface(pi, mdFrom, mdTo, method);
            if (instance != null) {
              return Future.succeededFuture(instance);
            }
            break;
          default:
            return Future.failedFuture(new OkapiError(ErrorType.USER,
                messages.getMessage("10401", v)));
        }
      }
    }
    return Future.succeededFuture(null);
  }

  private void putTenantParameters(JsonObject jo, String tenantParameters) {
    if (tenantParameters != null) {
      JsonArray ja = new JsonArray();
      for (String p : tenantParameters.split(",")) {
        String[] kv = p.split("=");
        if (kv.length > 0) {
          JsonObject jsonKv = new JsonObject();
          jsonKv.put("key", kv[0]);
          if (kv.length > 1) {
            jsonKv.put("value", kv[1]);
          }
          ja.add(jsonKv);
        }
      }
      jo.put("parameters", ja);
    }
  }

  private ModuleInstance getTenantInstanceForInterface(
      InterfaceDescriptor pi, ModuleDescriptor mdFrom,
      ModuleDescriptor mdTo, String method) {

    ModuleDescriptor md = mdTo != null ? mdTo : mdFrom;
    if ("system".equals(pi.getInterfaceType())) {
      List<RoutingEntry> res = pi.getAllRoutingEntries();
      for (RoutingEntry re : res) {
        if (re.match(null, method)) {
          String pattern = re.getStaticPath();
          if (method.equals("DELETE")) {
            return new ModuleInstance(md, re, pattern, HttpMethod.DELETE, true);
          } else if ("/_/tenant/disable".equals(pattern)) {
            if (mdTo == null) {
              return new ModuleInstance(md, re, pattern, HttpMethod.POST, true);
            }
          } else if (mdTo != null) {
            return new ModuleInstance(md, re, pattern, HttpMethod.POST, true).withRetry();
          }
        }
      }
    }
    return null;
  }

  /**
   * Find (the first) module that provides a given system interface. Module must
   * be enabled for the tenant.
   *
   * @param tenant tenant to check for
   * @param interfaceName system interface to search for
   * @return future with ModuleDescriptor result (== null for not found)
   */

  private Future<ModuleDescriptor> findSystemInterface(Tenant tenant, String interfaceName) {
    return getEnabledModules(tenant).compose(res -> {
      for (ModuleDescriptor md : res) {
        if (md.getSystemInterface(interfaceName) != null) {
          return Future.succeededFuture(md);
        }
      }
      return Future.succeededFuture(null);
    });
  }

  Future<List<InterfaceDescriptor>> listInterfaces(String tenantId, boolean full,
                                                   String interfaceType) {
    return tenants.getNotFound(tenantId)
        .compose(tres -> listInterfaces(tres, full, interfaceType));
  }

  private Future<List<InterfaceDescriptor>> listInterfaces(Tenant tenant, boolean full,
                                                           String interfaceType) {
    return getEnabledModules(tenant).compose(modlist -> {
      List<InterfaceDescriptor> intList = new LinkedList<>();
      Set<String> ids = new HashSet<>();
      for (ModuleDescriptor md : modlist) {
        for (InterfaceDescriptor provide : md.getProvidesList()) {
          if (interfaceType == null || provide.isType(interfaceType)) {
            if (full) {
              intList.add(provide);
            } else {
              if (ids.add(provide.getId())) {
                InterfaceDescriptor tmp = new InterfaceDescriptor();
                tmp.setId(provide.getId());
                tmp.setVersion(provide.getVersion());
                intList.add(tmp);
              }
            }
          }
        }
      }
      return Future.succeededFuture(intList);
    });
  }

  Future<List<ModuleDescriptor>> listModulesFromInterface(
      String tenantId, String interfaceName, String interfaceType) {

    return tenants.getNotFound(tenantId).compose(tenant -> {
      List<ModuleDescriptor> mdList = new LinkedList<>();
      return getEnabledModules(tenant).compose(modlist -> {
        for (ModuleDescriptor md : modlist) {
          for (InterfaceDescriptor provide : md.getProvidesList()) {
            if (interfaceName.equals(provide.getId())
                && (interfaceType == null || provide.isType(interfaceType))) {
              mdList.add(md);
              break;
            }
          }
        }
        return Future.succeededFuture(mdList);
      });
    });
  }

  Future<InstallJob> installUpgradeGet(String tenantId, String installId) {
    return tenants.getNotFound(tenantId).compose(x -> jobs.getNotFound(tenantId, installId));
  }

  Future<Void> installUpgradeDelete(String tenantId, String installId) {
    return installUpgradeGet(tenantId, installId).compose(job -> {
      if (!Boolean.TRUE.equals(job.getComplete())) {
        return Future.failedFuture(new OkapiError(ErrorType.USER,
            messages.getMessage("10406", installId)));
      }
      return jobs.removeNotFound(tenantId, installId);
    });
  }

  Future<List<InstallJob>> installUpgradeGetList(String tenantId) {
    return tenants.getNotFound(tenantId).compose(x -> jobs.get(tenantId).compose(list -> {
      if (list == null) {
        return Future.succeededFuture(new LinkedList<>());
      }
      return Future.succeededFuture(list);
    }));
  }

  Future<Void> installUpgradeDeleteList(String tenantId) {
    return installUpgradeGetList(tenantId).compose(res -> {
      Future<Void> future = Future.succeededFuture();
      for (InstallJob job : res) {
        if (Boolean.TRUE.equals(job.getComplete())) {
          future = future.compose(x -> jobs.removeNotFound(tenantId, job.getId()));
        }
      }
      return future;
    });
  }

  Future<List<TenantModuleDescriptor>> installUpgradeCreate(
      String tenantId, String installId, ProxyContext pc,
      TenantInstallOptions options, List<TenantModuleDescriptor> tml) {

    logger.info("installUpgradeCreate InstallId={}", installId);
    if (tml != null) {
      for (TenantModuleDescriptor tm : tml) {
        if (tm.getAction() == null) {
          return Future.failedFuture(new OkapiError(ErrorType.USER,
              messages.getMessage("10405", tm.getId())));
        }
      }
    }
    return tenants.getNotFound(tenantId).compose(tenant ->
        moduleManager.getModulesWithFilter(options.getPreRelease(),
            options.getNpmSnapshot(), null)
            .compose(modules -> {
              HashMap<String, ModuleDescriptor> modsAvailable = new HashMap<>(modules.size());
              HashMap<String, ModuleDescriptor> modsEnabled = new HashMap<>();
              for (ModuleDescriptor md : modules) {
                modsAvailable.put(md.getId(), md);
                logger.info("mod available: {}", md.getId());
                if (tenant.isEnabled(md.getId())) {
                  logger.info("mod enabled: {}", md.getId());
                  modsEnabled.put(md.getId(), md);
                }
              }
              InstallJob job = new InstallJob();
              job.setId(installId);
              job.setStartDate(Instant.now().toString());
              if (tml == null) {
                job.setModules(upgrades(modsAvailable, modsEnabled));
              } else {
                job.setModules(tml);
              }
              job.setComplete(false);
              return runJob(tenant, pc, options, modsAvailable, modsEnabled, job);
            }));
  }

  private List<TenantModuleDescriptor> upgrades(
      Map<String, ModuleDescriptor> modsAvailable, Map<String, ModuleDescriptor> modsEnabled) {

    List<TenantModuleDescriptor> tml = new LinkedList<>();
    for (String id : modsEnabled.keySet()) {
      ModuleId moduleId = new ModuleId(id);
      String latestId = moduleId.getLatest(modsAvailable.keySet());
      if (!latestId.equals(id)) {
        TenantModuleDescriptor tmd = new TenantModuleDescriptor();
        tmd.setAction(Action.enable);
        tmd.setId(latestId);
        logger.info("upgrade.. enable {}", latestId);
        tmd.setFrom(id);
        tml.add(tmd);
      }
    }
    return tml;
  }

  private Future<List<TenantModuleDescriptor>> runJob(
      Tenant t, ProxyContext pc, TenantInstallOptions options,
      Map<String, ModuleDescriptor> modsAvailable,
      Map<String, ModuleDescriptor> modsEnabled, InstallJob job) {

    List<TenantModuleDescriptor> tml = job.getModules();
    return DepResolution.installSimulate(modsAvailable, modsEnabled, tml).compose(res -> {
      if (options.getSimulate()) {
        return Future.succeededFuture(tml);
      }
      return jobs.add(t.getId(), job.getId(), job).compose(res2 -> {
        Promise<List<TenantModuleDescriptor>> promise = Promise.promise();
        Future<Void> future = Future.succeededFuture();
        if (options.getAsync()) {
          List<TenantModuleDescriptor> tml2 = new LinkedList<>();
          for (TenantModuleDescriptor tm : tml) {
            tml2.add(tm.cloneWithoutStage());
          }
          promise.complete(tml2);
        }
        future = future.compose(x -> {
          for (TenantModuleDescriptor tm : tml) {
            tm.setStage(TenantModuleDescriptor.Stage.pending);
          }
          return jobs.put(t.getId(), job.getId(), job);
        });
        if (options.getDeploy()) {
          future = future.compose(x -> autoDeploy(t, job, modsAvailable, tml));
        }
        for (TenantModuleDescriptor tm : tml) {
          future = future.compose(x -> {
            tm.setStage(TenantModuleDescriptor.Stage.invoke);
            return jobs.put(t.getId(), job.getId(), job);
          });
          if (options.getIgnoreErrors()) {
            Promise<Void> promise1 = Promise.promise();
            installTenantModule(t, pc, options, modsAvailable, tm).onComplete(x -> {
              if (x.failed()) {
                logger.warn("Ignoring error for tenant {} module {}",
                    t.getId(), tm.getId(), x.cause());
              }
              promise1.complete();
            });
            future = future.compose(x -> promise1.future());
          } else {
            future = future.compose(x -> installTenantModule(t, pc, options, modsAvailable, tm));
          }
          future = future.compose(x -> {
            if (tm.getMessage() != null) {
              return Future.succeededFuture();
            }
            tm.setStage(TenantModuleDescriptor.Stage.done);
            return jobs.put(t.getId(), job.getId(), job);
          });
        }
        if (options.getDeploy()) {
          future.compose(x -> autoUndeploy(t, job, modsAvailable, tml));
        }
        for (TenantModuleDescriptor tm : tml) {
          future = future.compose(x -> {
            if (tm.getMessage() != null) {
              return Future.succeededFuture();
            }
            tm.setStage(TenantModuleDescriptor.Stage.done);
            return jobs.put(t.getId(), job.getId(), job);
          });
        }
        future.onComplete(x -> {
          job.setEndDate(Instant.now().toString());
          job.setComplete(true);
          jobs.put(t.getId(), job.getId(), job).onComplete(y -> logger.info("job complete"));
          if (options.getAsync()) {
            return;
          }
          if (x.failed()) {
            logger.warn("job failed", x.cause());
            promise.fail(x.cause());
            return;
          }
          List<TenantModuleDescriptor> tml2 = new LinkedList<>();
          for (TenantModuleDescriptor tm : tml) {
            tml2.add(tm.cloneWithoutStage());
          }
          promise.complete(tml2);
        });
        return promise.future();
      });
    });
  }

  private Future<Void> autoDeploy(Tenant tenant, InstallJob job, Map<String,
      ModuleDescriptor> modsAvailable, List<TenantModuleDescriptor> tml) {

    List<Future> futures = new LinkedList<>();
    for (TenantModuleDescriptor tm : tml) {
      if (tm.getAction() == Action.enable || tm.getAction() == Action.uptodate) {
        ModuleDescriptor md = modsAvailable.get(tm.getId());
        tm.setStage(TenantModuleDescriptor.Stage.deploy);
        futures.add(jobs.put(tenant.getId(), job.getId(), job).compose(res ->
            proxyService.autoDeploy(md)
                .onFailure(x -> tm.setMessage(x.getMessage()))));
      }
    }
    return CompositeFuture.all(futures).mapEmpty();
  }

  private Future<Void> installTenantModule(Tenant tenant, ProxyContext pc,
                                           TenantInstallOptions options,
                                           Map<String, ModuleDescriptor> modsAvailable,
                                           TenantModuleDescriptor tm) {
    ModuleDescriptor mdFrom = null;
    ModuleDescriptor mdTo = null;
    if (tm.getAction() == Action.enable) {
      if (tm.getFrom() != null) {
        mdFrom = modsAvailable.get(tm.getFrom());
      }
      mdTo = modsAvailable.get(tm.getId());
    } else if (tm.getAction() == Action.disable) {
      mdFrom = modsAvailable.get(tm.getId());
    }
    return enableAndDisableModule(tenant, options, mdFrom, mdTo, pc)
        .onFailure(x -> tm.setMessage(x.getMessage()))
        .mapEmpty();
  }

  private Future<Void> autoUndeploy(Tenant tenant, InstallJob job,
                                    Map<String, ModuleDescriptor> modsAvailable,
                                    List<TenantModuleDescriptor> tml) {

    List<Future> futures = new LinkedList<>();
    for (TenantModuleDescriptor tm : tml) {
      futures.add(autoUndeploy(tenant, job, modsAvailable, tm));
    }
    return CompositeFuture.all(futures).mapEmpty();
  }

  private Future<Void> autoUndeploy(Tenant tenant, InstallJob job,
                                    Map<String, ModuleDescriptor> modsAvailable,
                                    TenantModuleDescriptor tm) {
    ModuleDescriptor md = null;
    if (tm.getAction() == Action.enable) {
      md = modsAvailable.get(tm.getFrom());
    }
    if (tm.getAction() == Action.disable) {
      md = modsAvailable.get(tm.getId());
    }
    if (md == null) {
      return Future.succeededFuture();
    }
    final ModuleDescriptor mdF = md;
    return getModuleUser(md.getId()).compose(res -> {
      if (!res.isEmpty()) { // tenants using module, skip undeploy
        return Future.succeededFuture();
      }
      tm.setStage(TenantModuleDescriptor.Stage.undeploy);
      return jobs.put(tenant.getId(), job.getId(), job).compose(x ->
          proxyService.autoUndeploy(mdF));
    });
  }

  Future<List<ModuleDescriptor>> listModules(String id) {
    return tenants.getNotFound(id).compose(t -> {
      List<ModuleDescriptor> tl = new LinkedList<>();
      List<Future> futures = new LinkedList<>();
      for (String moduleId : t.listModules()) {
        futures.add(moduleManager.get(moduleId).compose(x -> {
          tl.add(x);
          return Future.succeededFuture();
        }));
      }
      return CompositeFuture.all(futures).map(tl);
    });
  }

  /**
   * Return tenants using module.
   * @param mod module Id
   * @return future with tenants that have this module enabled
   */
  public Future<List<String>> getModuleUser(String mod) {
    return tenants.getKeys().compose(kres -> {
      List<String> users = new LinkedList<>();
      List<Future> futures = new LinkedList<>();
      for (String tid : kres) {
        futures.add(tenants.get(tid).compose(t -> {
          if (t.isEnabled(mod)) {
            users.add(tid);
          }
          return Future.succeededFuture();
        }));
      }
      return CompositeFuture.all(futures).map(users);
    });
  }

  /**
   * Load tenants from the store into the shared memory map.
   *
   * @return fut future
   */
  private Future<Void> loadTenants() {
    return tenants.getKeys().compose(keys -> {
      if (!keys.isEmpty()) {
        return Future.succeededFuture();
      }
      return tenantStore.listTenants().compose(res -> {
        List<Future> futures = new LinkedList<>();
        for (Tenant t : res) {
          futures.add(tenants.add(t.getId(), t));
        }
        return CompositeFuture.all(futures).mapEmpty();
      });
    });
  }

  /**
   * Get module cache for tenant.
   * @param tenant Tenant
   * @return Module Cache
   */
  public Future<ModuleCache> getModuleCache(Tenant tenant) {
    if (!enabledModulesCache.containsKey(tenant.getId())) {
      return Future.succeededFuture(new ModuleCache(new LinkedList<>()));
    }
    return Future.succeededFuture(enabledModulesCache.get(tenant.getId()));
  }

  /**
   * Return modules enabled for tenant.
   * @param tenant Tenant
   * @return list of modules
   */
  public Future<List<ModuleDescriptor>> getEnabledModules(Tenant tenant) {
    return getModuleCache(tenant).map(cache -> cache.getModules());
  }

  private Future<Void> reloadEnabledModules(String tenantId) {
    return tenants.get(tenantId).compose(tenant -> {
      if (tenant == null) {
        enabledModulesCache.remove(tenantId);
        return Future.succeededFuture();
      }
      return reloadEnabledModules(tenant);
    });
  }

  private Future<Void> reloadEnabledModules(Tenant tenant) {
    if (moduleManager == null) {
      return Future.succeededFuture(); // only happens in tests really
    }
    List<ModuleDescriptor> mdl = new LinkedList<>();
    List<Future> futures = new LinkedList<>();
    for (String tenantId : tenant.getEnabled().keySet()) {
      futures.add(moduleManager.get(tenantId).compose(md -> {
        InterfaceDescriptor id = md.getSystemInterface("_tenantPermissions");
        if (id != null) {
          expandedModulesCache.put(tenant.getId(), !id.getVersion().equals("1.0"));
        }
        mdl.add(md);
        return Future.succeededFuture();
      }));
    }
    return CompositeFuture.all(futures).compose(res -> {
      enabledModulesCache.put(tenant.getId(), new ModuleCache(mdl));
      return Future.succeededFuture();
    });
  }

  /**
   * Return if permissions should be expanded.
   * @param tenantId Tenant ID
   * @return TRUE if expansion; FALSE if no expansion; null if not known
   */
  Boolean getExpandModulePermissions(String tenantId) {
    return expandedModulesCache.get(tenantId);
  }

  @Override
  public Future<Void> isAlive() {
    return tenantStore.listTenants().mapEmpty();
  }
} // class

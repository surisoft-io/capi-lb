package io.surisoft.capi.lb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.lb.cache.StickySessionCacheManager;
import io.surisoft.capi.lb.builder.DirectRouteProcessor;
import io.surisoft.capi.lb.builder.RestDefinitionProcessor;
import io.surisoft.capi.lb.processor.MetricsProcessor;
import io.surisoft.capi.lb.schema.*;
import io.surisoft.capi.lb.utils.ApiUtils;
import io.surisoft.capi.lb.utils.Constants;
import io.surisoft.capi.lb.utils.RouteUtils;
import okhttp3.*;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.util.json.JsonObject;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class ConsulNodeDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ConsulNodeDiscovery.class);

    private String consulHost;
    private final ApiUtils apiUtils;
    private final RouteUtils routeUtils;
    private final MetricsProcessor metricsProcessor;
    private final StickySessionCacheManager stickySessionCacheManager;
    private final OkHttpClient client = new OkHttpClient.Builder().build();
    private static final String GET_ALL_SERVICES = "/v1/catalog/services";
    private static final String GET_SERVICE_BY_NAME = "/v1/catalog/service/";
    private String capiContext;

    private final CamelContext camelContext;
    private final Cache<String, Api> apiCache;

    public ConsulNodeDiscovery(CamelContext camelContext, ApiUtils apiUtils, RouteUtils routeUtils, MetricsProcessor metricsProcessor, StickySessionCacheManager stickySessionCacheManager, Cache<String, Api> apiCache) {
        this.apiUtils = apiUtils;
        this.routeUtils = routeUtils;
        this.camelContext = camelContext;
        this.stickySessionCacheManager = stickySessionCacheManager;
        this.apiCache = apiCache;
        this.metricsProcessor = metricsProcessor;
    }

    public void processInfo() {
        getAllServices();
    }

    private void getAllServices() {
        log.trace("Querying Consul for new services");
        Request request = new Request.Builder().url(consulHost + GET_ALL_SERVICES).build();
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            public void onResponse(Call call, Response response) throws IOException {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonObject responseObject = objectMapper.readValue(response.body().string(), JsonObject.class);
                //We want to ignore the consul array for now...
                responseObject.remove("consul");
                Set<String> services = responseObject.keySet();
                try {
                    apiUtils.removeUnusedApi(camelContext, routeUtils, apiCache, services.stream().toList());
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                for(String service : services) {
                    getServiceByName(service);
                }
            }

            public void onFailure(Call call, IOException e) {
                //TODO
                log.info(e.getMessage());
            }
        });
    }

    private void getServiceByName(String serviceName) {
        log.trace("Processing service name: {}", serviceName);
        Request request = new Request.Builder().url(consulHost + GET_SERVICE_BY_NAME + serviceName).build();
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            public void onResponse(Call call, Response response) throws IOException {
                ObjectMapper objectMapper = new ObjectMapper();
                ConsulObject[] consulResponse = objectMapper.readValue(response.body().string(), ConsulObject[].class);
                Map<String, List<Mapping>> servicesStructure = groupByServiceId(consulResponse);

                for (var entry : servicesStructure.entrySet()) {
                    String apiId = serviceName + ":" + entry.getKey();
                    Api incomingApi = createApiObject(apiId, serviceName, entry.getKey(), entry.getValue(), consulResponse);
                    Api existingApi = apiCache.peek(apiId);
                    if(existingApi == null) {
                        createRoute(incomingApi);
                    } else {
                        apiUtils.updateExistingApi(existingApi, incomingApi, apiCache, routeUtils, metricsProcessor, camelContext, stickySessionCacheManager, capiContext);
                    }
                }

                try {
                    apiUtils.removeUnusedApi(camelContext, routeUtils, apiCache, servicesStructure, serviceName);
                } catch(Exception e) {
                    log.error(e.getMessage(), e);
                }
            }

            public void onFailure(Call call, IOException e) {
                log.error(e.getMessage(), e);
            }
        });
    }

    private Map<String, List<Mapping>> groupByServiceId(ConsulObject[] consulService) {
        Map<String, List<Mapping>> groupedService = new HashMap<>();
        Set<String> serviceIdList = new HashSet<>();
        for(ConsulObject serviceIdEntry : consulService) {
            String serviceNodeGroup = getServiceNodeGroup(serviceIdEntry);
            if(serviceNodeGroup != null) {
                serviceIdList.add(serviceNodeGroup);
            } else {
                log.trace("Service Tag group not present, service will not be deployed");
            }
        }
        for (String id : serviceIdList) {
            List<Mapping> mappingList = new ArrayList<>();
            for (ConsulObject serviceIdToProcess : consulService) {
                String serviceNodeGroup = getServiceNodeGroup(serviceIdToProcess);
                if (id.equals(serviceNodeGroup)) {
                    Mapping entryMapping = apiUtils.consulObjectToMapping(serviceIdToProcess);
                    mappingList.add(entryMapping);
                }
            }
            groupedService.put(id, mappingList);
        }
        return groupedService;
    }

    private String getServiceNodeGroup(ConsulObject consulObject) {
        for(String serviceTag : consulObject.getServiceTags()) {
            if(serviceTag.contains(Constants.CONSUL_GROUP)) {
                return serviceTag.substring(serviceTag.lastIndexOf("=") + 1);
            }
        }
        return null;
    }

    private boolean forwardPrefix(String tagName, ConsulObject[] consulObject) {
        for(ConsulObject entry : consulObject) {
            if(entry.getServiceTags().contains(Constants.CONSUL_GROUP + tagName) && entry.getServiceTags().contains(Constants.X_FORWARDED_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public HttpProtocol getHttpProtocol(String serviceName, String key, ConsulObject[] consulObject) {
        for(ConsulObject entry : consulObject) {
            if(entry.getServiceName().equals(serviceName) &&
                    entry.getServiceTags().contains(Constants.CONSUL_GROUP + key) &&
                    entry.getServiceTags().contains(Constants.HTTPS_CONSUL_SERVICE_TAG)) {
                return HttpProtocol.HTTPS;
            }
        }
        return HttpProtocol.HTTP;
    }

    private boolean showZipkinTraceId(String tagName, ConsulObject[] consulObject) {
        for(ConsulObject entry : consulObject) {
            if(entry.getServiceTags().contains(Constants.CONSUL_GROUP + tagName) && entry.getServiceTags().contains(Constants.TRACE_ID_HEADER)) {
                return true;
            }
        }
        return false;
    }

    private Api createApiObject(String apiId, String serviceName, String key, List<Mapping> mappingList, ConsulObject[] consulResponse) {
        Api incomingApi = new Api();
        incomingApi.setId(apiId);
        incomingApi.setName(serviceName);
        incomingApi.setContext("/" + serviceName + "/" + key);
        incomingApi.setHttpMethod(HttpMethod.ALL);
        incomingApi.setPublished(true);
        incomingApi.setMappingList(new ArrayList<>());
        incomingApi.setFailoverEnabled(true);
        incomingApi.setRoundRobinEnabled(true);
        incomingApi.setMatchOnUriPrefix(true);
        incomingApi.setMappingList(mappingList);
        incomingApi.setForwardPrefix(forwardPrefix(key, consulResponse));
        incomingApi.setZipkinShowTraceId(showZipkinTraceId(key, consulResponse));
        incomingApi.setHttpProtocol(getHttpProtocol(serviceName, key, consulResponse));
        return incomingApi;
    }

    private void createRoute(Api incomingApi) {
        apiCache.put(incomingApi.getId(), incomingApi);
        List<String> apiRouteIdList = routeUtils.getAllRouteIdForAGivenApi(incomingApi);
        for(String routeId : apiRouteIdList) {
            Route existingRoute = camelContext.getRoute(routeId);
            if(existingRoute == null) {
                try {
                    camelContext.addRoutes(new RestDefinitionProcessor(camelContext, incomingApi, routeUtils, routeId));
                    camelContext.addRoutes(new DirectRouteProcessor(camelContext, incomingApi, routeUtils, metricsProcessor, routeId, stickySessionCacheManager, capiContext));
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    public void setConsulHost(String consulHost) {
        this.consulHost = consulHost;
    }

    public void setCapiContext(String capiContext) {
        this.capiContext = capiContext;
    }
}
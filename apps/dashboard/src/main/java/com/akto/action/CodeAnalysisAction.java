package com.akto.action;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.types.Code;
import org.bson.types.ObjectId;
import org.checkerframework.checker.units.qual.s;

import com.akto.action.observe.Utils;
import com.akto.dao.ApiCollectionsDao;
import com.akto.dao.CodeAnalysisApiInfoDao;
import com.akto.dao.CodeAnalysisCollectionDao;
import com.akto.dao.test_editor.YamlTemplateDao;
import com.akto.dto.ApiCollection;
import com.akto.dto.CodeAnalysisApi;
import com.akto.dto.CodeAnalysisApiInfo;
import com.akto.dto.CodeAnalysisApiLocation;
import com.akto.dto.CodeAnalysisCollection;
import com.akto.dto.test_editor.YamlTemplate;
import com.akto.dto.type.SingleTypeInfo.SuperType;
import com.akto.listener.InitializerListener;
import com.akto.log.LoggerMaker;
import com.akto.log.LoggerMaker.LogDb;
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

public class CodeAnalysisAction extends UserAction {

    private String projectDir;
    private String apiCollectionName;
    private Map<String, CodeAnalysisApi> codeAnalysisApisMap;

    private static final LoggerMaker loggerMaker = new LoggerMaker(CodeAnalysisAction.class);
    
    public String syncExtractedAPIs() {
        loggerMaker.infoAndAddToDb("Syncing code analysis endpoints for collection: " + apiCollectionName, LogDb.DASHBOARD);

        // todo:  If API collection does exist, create it
        ApiCollection apiCollection = ApiCollectionsDao.instance.findByName(apiCollectionName);
        if (apiCollection == null) {
            loggerMaker.errorAndAddToDb("API collection not found " + apiCollectionName, LogDb.DASHBOARD);
            addActionError("API collection not found: " + apiCollectionName);
            return ERROR.toUpperCase();
        }

        /*
         * In some cases it is not possible to determine the type of template url from source code
         * In such cases, we can use the information from traffic endpoints to match the traffic and source code endpoints
         * 
         * Eg:
         * Source code endpoints:
         * GET /books/STRING -> GET /books/AKTO_TEMPLATE_STR -> GET /books/INTEGER
         * POST /city/STRING/district/STRING -> POST /city/AKTO_TEMPLATE_STR/district/AKTO_TEMPLATE_STR -> POST /city/STRING/district/INTEGER
         * Traffic endpoints:
         * GET /books/INTEGER -> GET /books/AKTO_TEMPLATE_STR
         * POST /city/STRING/district/INTEGER -> POST /city/AKTO_TEMPLATE_STR/district/AKTO_TEMPLATE_STR
         */
        List<BasicDBObject> trafficApis = Utils.fetchEndpointsInCollectionUsingHost(apiCollection.getId(), 0);
        Map<String, String> trafficApiEndpointAktoTemplateStrToOriginalMap = new HashMap<>();
        List<String> trafficApiKeys = new ArrayList<>();
        for (BasicDBObject trafficApi: trafficApis) {
            BasicDBObject trafficApiApiInfoKey = (BasicDBObject) trafficApi.get("_id");
            String trafficApiMethod = trafficApiApiInfoKey.getString("method");
            String trafficApiUrl = trafficApiApiInfoKey.getString("url");
            String trafficApiEndpoint = "";

            // extract path name from url
            try {
                URL url = new URL(trafficApiUrl);
                trafficApiEndpoint = url.getPath(); 

                trafficApiEndpoint = new URI(trafficApiEndpoint).getPath()
                        .replace("%7B", "{")
                        .replace("%7D", "}");
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Error parsing URL: " + trafficApiUrl, LogDb.DASHBOARD);
            }

            // Ensure endpoint doesn't end with a slash
            if (trafficApiEndpoint.length() > 1 && trafficApiEndpoint.endsWith("/")) {
                trafficApiEndpoint = trafficApiEndpoint.substring(0, trafficApiEndpoint.length() - 1);
            }

            String trafficApiKey = trafficApiMethod + " " + trafficApiEndpoint;
            trafficApiKeys.add(trafficApiKey);

            String trafficApiEndpointAktoTemplateStr = trafficApiEndpoint;

            for (SuperType type : SuperType.values()) {
                // Replace each occurrence of Akto template url format with"AKTO_TEMPLATE_STRING"
                trafficApiEndpointAktoTemplateStr = trafficApiEndpointAktoTemplateStr.replace(type.name(), "AKTO_TEMPLATE_STR");
            }

            trafficApiEndpointAktoTemplateStrToOriginalMap.put(trafficApiEndpointAktoTemplateStr, trafficApiEndpoint);
        }

        Map<String, CodeAnalysisApi> tempCodeAnalysisApisMap = new HashMap<>(codeAnalysisApisMap);
        for (Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: codeAnalysisApisMap.entrySet()) {
            String codeAnalysisApiKey = codeAnalysisApiEntry.getKey();
            CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();

            String codeAnalysisApiEndpoint = codeAnalysisApi.getEndpoint();

            String codeAnalysisApiEndpointAktoTemplateStr = codeAnalysisApiEndpoint;

            for (SuperType type : SuperType.values()) {
                // Replace each occurrence of Akto template url format with "AKTO_TEMPLATE_STRING"
                codeAnalysisApiEndpointAktoTemplateStr = codeAnalysisApiEndpointAktoTemplateStr.replace(type.name(), "AKTO_TEMPLATE_STR");
            }

            if(codeAnalysisApiEndpointAktoTemplateStr.contains("AKTO_TEMPLATE_STR") && trafficApiEndpointAktoTemplateStrToOriginalMap.containsKey(codeAnalysisApiEndpointAktoTemplateStr)) {
               CodeAnalysisApi newCodeAnalysisApi = new CodeAnalysisApi(
                    codeAnalysisApi.getMethod(), 
                    trafficApiEndpointAktoTemplateStrToOriginalMap.get(codeAnalysisApiEndpointAktoTemplateStr), 
                    codeAnalysisApi.getLocation());
                
                tempCodeAnalysisApisMap.remove(codeAnalysisApiKey);
                tempCodeAnalysisApisMap.put(newCodeAnalysisApi.generateCodeAnalysisApisMapKey(), newCodeAnalysisApi);
            }
        }


        /*
         * Match endpoints between traffic and source code endpoints, when only method is different
         * Eg:
         * Source code endpoints:
         * POST /books
         * Traffic endpoints:
         * PUT /books
         * Add PUT /books to source code endpoints
         */
        for(String trafficApiKey: trafficApiKeys) {
            if (!codeAnalysisApisMap.containsKey(trafficApiKey)) {
                for(Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: tempCodeAnalysisApisMap.entrySet()) {
                    CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();
                    String codeAnalysisApiEndpoint = codeAnalysisApi.getEndpoint();
                   
                    String trafficApiMethod = "", trafficApiEndpoint = "";
                    try {
                        String[] trafficApiKeyParts = trafficApiKey.split(" ");
                        trafficApiMethod = trafficApiKeyParts[0];
                        trafficApiEndpoint = trafficApiKeyParts[1];
                    } catch (Exception e) {
                        loggerMaker.errorAndAddToDb("Error parsing traffic API key: " + trafficApiKey, LogDb.DASHBOARD);
                    }

                    if (codeAnalysisApiEndpoint.equals(trafficApiEndpoint)) {
                        CodeAnalysisApi newCodeAnalysisApi = new CodeAnalysisApi(
                            trafficApiMethod, 
                            trafficApiEndpoint, 
                            codeAnalysisApi.getLocation());
                        
                        tempCodeAnalysisApisMap.put(newCodeAnalysisApi.generateCodeAnalysisApisMapKey(), newCodeAnalysisApi);
                        break;
                    }
                }
            }
        }

        codeAnalysisApisMap = tempCodeAnalysisApisMap;

        ObjectId codeAnalysisCollectionId = null;
        try {
            // ObjectId for new code analysis collection
            codeAnalysisCollectionId = new ObjectId();

            CodeAnalysisCollection codeAnalysisCollection = CodeAnalysisCollectionDao.instance.updateOne(
                Filters.eq("codeAnalysisCollectionName", apiCollectionName),
                Updates.combine(
                        Updates.setOnInsert(CodeAnalysisCollection.ID, codeAnalysisCollectionId),
                        Updates.setOnInsert(CodeAnalysisCollection.NAME, apiCollectionName),
                        Updates.set(CodeAnalysisCollection.PROJECT_DIR, projectDir)
                )
            );

            // Set code analysis collection id if existing collection is updated
            if (codeAnalysisCollection != null) {
                codeAnalysisCollectionId = codeAnalysisCollection.getId();
            }
        } catch (Exception e) {
            loggerMaker.errorAndAddToDb("Error updating code analysis collection: " + apiCollectionName + " Error: " + e.getMessage(), LogDb.DASHBOARD);
            addActionError("Error syncing code analysis collection: " + apiCollectionName);
            return ERROR.toUpperCase();
        }
       
        if (codeAnalysisCollectionId != null) {
            List<WriteModel<CodeAnalysisApiInfo>> bulkUpdates = new ArrayList<>();

            for(Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: codeAnalysisApisMap.entrySet()) {
                    CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();
                    CodeAnalysisApiInfo.CodeAnalysisApiInfoKey codeAnalysisApiInfoKey = new CodeAnalysisApiInfo.CodeAnalysisApiInfoKey(codeAnalysisCollectionId, codeAnalysisApi.getMethod(), codeAnalysisApi.getEndpoint());

                    bulkUpdates.add(
                        new UpdateOneModel<>(
                            Filters.eq(CodeAnalysisApiInfo.ID, codeAnalysisApiInfoKey),
                            Updates.combine(
                                Updates.setOnInsert(CodeAnalysisApiInfo.ID, codeAnalysisApiInfoKey),
                                Updates.set(CodeAnalysisApiInfo.LOCATION, codeAnalysisApi.getLocation())
                            ),
                            new UpdateOptions().upsert(true)
                        )
                    );
            }

            try {
                CodeAnalysisApiInfoDao.instance.getMCollection().bulkWrite(bulkUpdates);
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Error updating code analysis api infos: " + apiCollectionName + " Error: " + e.getMessage(), LogDb.DASHBOARD);
                addActionError("Error syncing code analysis collection: " + apiCollectionName);
                return ERROR.toUpperCase();
            }
        }

        loggerMaker.infoAndAddToDb("Updated code analysis collection: " + apiCollectionName, LogDb.DASHBOARD);
        loggerMaker.infoAndAddToDb("Source code endpoints count: " + codeAnalysisApisMap.size(), LogDb.DASHBOARD);

        return SUCCESS.toUpperCase();
    }

    public String getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }

    public String getApiCollectionName() {
        return apiCollectionName;
    }

    public void setApiCollectionName(String apiCollectionName) {
        this.apiCollectionName = apiCollectionName;
    }

    public Map<String, CodeAnalysisApi> getCodeAnalysisApisMap() {
        return codeAnalysisApisMap;
    }

    public void setCodeAnalysisApisMap(Map<String, CodeAnalysisApi> codeAnalysisApisMap) {
        this.codeAnalysisApisMap = codeAnalysisApisMap;
    }
}

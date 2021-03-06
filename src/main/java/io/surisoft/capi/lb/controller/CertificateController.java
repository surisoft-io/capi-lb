package io.surisoft.capi.lb.controller;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *     contributor license agreements.  See the NOTICE file distributed with
 *     this work for additional information regarding copyright ownership.
 *     The ASF licenses this file to You under the Apache License, Version 2.0
 *     (the "License"); you may not use this file except in compliance with
 *     the License.  You may obtain a copy of the License at
 *          http://www.apache.org/licenses/LICENSE-2.0
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

import io.surisoft.capi.lb.schema.AliasInfo;
import io.surisoft.capi.lb.utils.Constants;
import io.surisoft.capi.lb.utils.RouteUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@RestController
@RequestMapping("/manager/certificate")
@Tag(name="Certificate Management")
public class CertificateController {

    private static final Logger log = LoggerFactory.getLogger(CertificateController.class);

    @Value("${capi.trust.store.path}")
    private String capiTrustStorePath;

    @Value("${capi.trust.store.password}")
    private String capiTrustStorePassword;

    @Value("${capi.trust.store.enabled}")
    private boolean capiTrustStoreEnabled;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    RouteUtils routeUtils;

    @Operation(summary = "Get all certificates")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All certificates trusted by CAPI")
    })
    @GetMapping
    public ResponseEntity<List<AliasInfo>> getAll() {
        List<AliasInfo> aliasList = new ArrayList<>();

        if(!capiTrustStoreEnabled) {
            AliasInfo aliasInfo = new AliasInfo();
            aliasInfo.setAdditionalInfo(Constants.NO_CUSTOM_TRUST_STORE_PROVIDED);
            aliasList.add(aliasInfo);
            return new ResponseEntity<>(aliasList, HttpStatus.OK);
        }

        try(InputStream is = getInputStream()) {
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(is, capiTrustStorePassword.toCharArray());
            Enumeration<String> aliases = keystore.aliases();
            while(aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                AliasInfo aliasInfo = new AliasInfo();
                aliasInfo.setAlias(alias);
                X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
                aliasInfo.setIssuerDN(certificate.getIssuerX500Principal().getName());
                aliasInfo.setSubjectDN(certificate.getSubjectX500Principal().getName());
                aliasList.add(aliasInfo);
            }
        } catch(Exception e) {
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(aliasList, HttpStatus.OK);
    }

    @Operation(summary = "Add a certificate to CAPI Gateway trusted store.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Certificate trusted"),
            @ApiResponse(responseCode = "400", description = "Custom Trust store not detected")
    })
    @PostMapping(path = "/{alias}/{apiId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AliasInfo> certificateUpload(@PathVariable String alias, @PathVariable String apiId, @RequestPart("file") MultipartFile file) {
        AliasInfo aliasInfo = new AliasInfo();

        if(!capiTrustStoreEnabled) {
            aliasInfo = new AliasInfo();
            aliasInfo.setAdditionalInfo(Constants.NO_CUSTOM_TRUST_STORE_PROVIDED);
            return new ResponseEntity<>(aliasInfo, HttpStatus.BAD_REQUEST);
        }

        try(InputStream is = getInputStream()) {
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(is, capiTrustStorePassword.toCharArray());

            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate newTrusted = certificateFactory.generateCertificate(file.getInputStream());

            X509Certificate x509Object = (X509Certificate) newTrusted;
            aliasInfo.setSubjectDN(x509Object.getSubjectX500Principal().getName());
            aliasInfo.setIssuerDN(x509Object.getIssuerX500Principal().getName());
            aliasInfo.setAlias(alias);
            aliasInfo.setApiId(apiId);

            keystore.setCertificateEntry(alias + ":" + apiId, newTrusted);

            try(OutputStream storeOutputStream = getOutputStream()) {
                keystore.store(storeOutputStream, capiTrustStorePassword.toCharArray());
            }
            routeUtils.reloadTrustStoreManager(apiId, false);
        } catch (Exception e) {
           log.debug(e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);

        }
        return new ResponseEntity<>(aliasInfo, HttpStatus.OK);
    }

    @Operation(summary = "Remove a certificate from CAPI Gateway trusted store.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Certificate removed"),
            @ApiResponse(responseCode = "400", description = "Custom Trust store not detected")
    })
    @DeleteMapping(path = "/{alias}/{apiId}")
    public ResponseEntity<AliasInfo> removeFromTrust(@PathVariable String alias, @PathVariable String apiId) {
        AliasInfo aliasInfo = new AliasInfo();

        if(!capiTrustStoreEnabled) {
            aliasInfo.setAdditionalInfo(Constants.NO_CUSTOM_TRUST_STORE_PROVIDED);
            return new ResponseEntity<>(aliasInfo, HttpStatus.BAD_REQUEST);
        }

        aliasInfo.setAlias(alias);

        try(InputStream is = getInputStream()) {
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(is, capiTrustStorePassword.toCharArray());

            keystore.deleteEntry(alias + ":" + apiId);

            try(OutputStream storeOutputStream = getOutputStream()) {
                keystore.store(storeOutputStream, capiTrustStorePassword.toCharArray());
            }
            routeUtils.reloadTrustStoreManager(apiId, true);
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(aliasInfo, HttpStatus.OK);
    }

    private InputStream getInputStream() throws IOException {
        log.trace(capiTrustStorePath);
        if(capiTrustStorePath.startsWith("classpath")) {
            return resourceLoader.getResource(capiTrustStorePath).getInputStream();
        } else {
            return new FileInputStream(capiTrustStorePath);
        }
    }

    private OutputStream getOutputStream() throws IOException {
        if(capiTrustStorePath.startsWith("classpath")) {
             return new FileOutputStream(resourceLoader.getResource(capiTrustStorePath).getFile());
        } else {
            return new FileOutputStream(capiTrustStorePath);
        }
    }
}
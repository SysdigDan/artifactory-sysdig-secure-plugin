/*
 * Copyright (C) 2021 Sysdig Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.collect.BiMap
import com.google.common.collect.ImmutableBiMap
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.util.regex.Pattern
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Globals {

    static final SUPPORT_MATRIX = [
        "storage": [
            "afterCreate": [ name: "storage.afterCreate", description: "Called after artifact creation operation",
                             humanName: "Artifact created"]
        ],
        "docker": [
            "tagCreated": [ name: "docker.tagCreated", description: "Called after a tag is created",
                            humanName: "Docker tag created"]
        ]
    ]

    static final RESPONSE_FORMATTER_MATRIX = [
        default: [
            description: "Sysdig default formatter", formatter: new ResponseFormatter ( )
        ]
    ]

    static SUPPORTED_EVENTS = [ ].toSet ( )
    static {
        SUPPORT_MATRIX.each {
            k, v ->
                v.each { k1, v1 ->
                    SUPPORTED_EVENTS.add(v1.name)
                }
        }
    }

    static def eventToSupported(String event) {
        def idx = event.indexOf('.')
        return SUPPORT_MATRIX[event.substring(0, idx)][event.substring(idx + 1)]
    }
    enum PackageTypeEnum {
        DOCKER ("docker/image")

        private String value

        PackageTypeEnum(String value) {
            this.value = value
        }

        String toString() {
            return value;
        }
    }
    static final BiMap<String, PackageTypeEnum> PACKAGE_TYPE_MAP = new ImmutableBiMap.Builder<String, PackageTypeEnum>()
            .put("docker", PackageTypeEnum.DOCKER)
            .build()
    static repositories
}

SysdigPlugin.init(ctx, log)

executions {

    Globals.repositories = repositories

    pingSysdig(httpMethod: 'GET') {
        def json = new JsonBuilder()
        json (
                message: "It works!",
        )
        hook(Globals.SUPPORT_MATRIX.execute.pingSysdig.name, json)
    }

    pluginReload (httpMethod: 'POST') {
        SysdigPlugin.reload()
        message = "Reloaded!\n"
    }
}

storage {
    afterCreate { item ->
        hook(Globals.SUPPORT_MATRIX.storage.afterCreate.name, item ? new JsonBuilder(item) : null)
    }
}

class ResponseFormatter {
    def format(String event, JsonBuilder data) {
        def eventTypeMetadata = Globals.eventToSupported(event)
        def builder = new JsonBuilder()
        def json = data.content

        if (Globals.SUPPORT_MATRIX.docker.tagCreated.name == eventTypeMetadata.name) {
            builder {
                tag "${SysdigPlugin.baseUrl()}/${json.docker.image}:${json.docker.tag}"
                annotations(
                    "added-by": "cicd-scan-request",
                )
            }
        } else{
            builder {
                text "Artifactory: ${eventTypeMetadata['humanName']} event is not supported by Sysdig formatter"
            }
        }
        return builder
    }
}

def dockerDataDecorator(JsonBuilder data) {
    def tagName = null, imageName = null
    def path = data.content.relPath
    def m = path =~ /^(.*)\/(.*?)\/manifest.json$/
    if (m[0] && m[0].size() == 3)
    {
        imageName = m[0][1]
        tagName = m[0][2]
    }
    def builder = new JsonBuilder()
    builder {
        docker(
            [
                "tag": tagName,
                "image": imageName
            ]
        )
        event data.content
    }
    return builder

}

def dockerEventDecoratorWork(String event, JsonBuilder data) {
    def json = data.content
    def repoKey = json.repoKey
    def repoInfo = repositories.getRepositoryConfiguration(repoKey)
    if (repoInfo && repoInfo.isEnableDockerSupport())
        if (json.name && json.name == "manifest.json")
            hook(event, dockerDataDecorator(data))
}

def dockerEventDecorator(String event, JsonBuilder data) {
    // New tag creation
    if (event == Globals.SUPPORT_MATRIX.storage.afterCreate.name)
        dockerEventDecoratorWork(Globals.SUPPORT_MATRIX.docker.tagCreated.name, data)
}

def hook(String event, JsonBuilder data) {
    try {
        if (SysdigPlugin.failedToLoadConfig) {
            log.error("Failed to load configuration from SysdigSecurePlugin.properties. Verify that it is valid JSON.")
            return
        }
        if (Globals.SUPPORTED_EVENTS.contains(event) && SysdigPlugin.active(event)) {
            log.info("Plugin being triggered for event '${event}'")
            log.trace(data.toString())
            SysdigPlugin.run(event, data)
        }
        // Docker decorator should occur after the basic event  even if we don't care about the basic event
        dockerEventDecorator(event, data)
    } catch (Exception ex) {
        // Don't risk failing the event by throwing an exception
        if (SysdigPlugin.debug())
            ex.printStackTrace()
        log.error("Plugin threw an exception: " + ex.getMessage())
    }
}

class SysdigPlugin {
    private static SysdigPlugin p
    private static final int MAX_TIMEOUT = 60000
    private static final String REPO_KEY_NAME = "repoKey"
    private static final responseFormatters = Globals.RESPONSE_FORMATTER_MATRIX
    public static boolean failedToLoadConfig = false
    def triggers = new HashMap()
    def debug = false
    def connectionTimeout = 15000
    def baseUrl
    def ctx = null
    def log = null

    ExecutorService excutorService = Executors.newFixedThreadPool(10)

    static void run(String event, Object json) {
        p.process(event, json)
    }

    static boolean active(String event) {
        if (p.triggers.get(event)) {
            return true
        } else {
            return false
        }
    }

    static boolean debug() {
        return p != null && p.debug == true
    }

    static String baseUrl() {
        return p.baseUrl
    }

    private String getFormattedJSONString(JsonBuilder json, String event, SysdigEndpointDetails plugin) {
        if (plugin.isDefaultFormat() || !responseFormatters.containsKey(plugin.format)) {
            return (responseFormatters['default'].formatter.format(event, json)).toString()
        }
        return (responseFormatters[plugin.format].formatter.format(event, json)).toString()
    }

    private void process(String event, Object json) {
        if (active(event)) {
            def pluginListeners = triggers.get(event)
            if (pluginListeners) {
                // We need to do this twice to do all async first
                for (SysdigEndpointDetails pluginListener : pluginListeners) {
                    try {
                        if (pluginListener.isAsync()) {
                            if (eventPassedFilters(event, json, pluginListener))
                                excutorService.execute(
                                        new PostTask(pluginListener.url, pluginListener.token, getFormattedJSONString(json, event, pluginListener)))
                        }
                    } catch (Exception e) {
                        // We don't capture async results
                        if (debug)
                            e.printStackTrace()
                    }
                }
                for (def pluginListener : pluginListeners) {
                    try {
                        if (!pluginListener.isAsync())
                            if (eventPassedFilters(event, json, pluginListener))
                                callPost(pluginListener.url, pluginListener.token, getFormattedJSONString(json, event, pluginListener))
                    } catch (Exception e) {
                        if (debug)
                            e.printStackTrace()
                    }
                }
            }
        }
    }

    private boolean eventPassedFilters(String event, Object json, SysdigEndpointDetails plugin) {
        return eventPassedDirectoryFilter(event, json) && eventPassedRepositoryAndPathFilter(event, json, plugin)
    }

    private boolean eventPassedRepositoryAndPathFilter(String event, Object json, SysdigEndpointDetails plugin) {
        boolean passesFilter = true
        def jsonData = null // Don't slurp unless necessary to avoid overhead
        if (event.startsWith("storage") || event.startsWith("docker")) {
            // Check repo if needed
            if (!plugin.allRepos()) {
                jsonData = new JsonSlurper().parseText(json.toString())
                def reposInEvent = findDeep(jsonData, REPO_KEY_NAME)
                boolean found = false
                if (reposInEvent != null && reposInEvent.size() > 0) {
                    reposInEvent.each {
                        if (plugin.appliesToRepo(it))
                            found = true
                    }
                }
                passesFilter = found
            }
            if (passesFilter && plugin.hasPathFilter()) { // Check path if needed
                if (jsonData == null)
                    jsonData = new JsonSlurper().parseText(json.toString())
                def matches = false
                if (event.startsWith("docker")) {
                    matches = plugin.matchesPathFilter(jsonData.docker.image) &&
                        plugin.matchesTagFilter(jsonData.docker.tag)
                } else if (event.startsWith("storage")) {
                    if (Globals.SUPPORT_MATRIX.storage.afterMove.name == event ||
                            Globals.SUPPORT_MATRIX.storage.afterCopy.name == event) {
                        matches = plugin.matchesPathFilter(jsonData.item.relPath) ||
                                plugin.matchesPathFilter(jsonData.targetRepoPath.relPath)
                    } else if (Globals.SUPPORT_MATRIX.storage.afterPropertyCreate.name == event ||
                            Globals.SUPPORT_MATRIX.storage.afterPropertyDelete.name == event) {
                        matches =  plugin.matchesPathFilter(jsonData.item.relPath)
                    } else {
                        matches =  plugin.matchesPathFilter(jsonData.relPath)
                    }
                }
                passesFilter = matches
            }
        }
        return passesFilter
    }

    private boolean eventPassedDirectoryFilter(String event, Object object) {
        if (event.startsWith("storage")) {
            def json = object.content
            if (json.folder)
                return false
        }
        return true
    }

    private String callPost(String urlString, String token, String content) {
        def url = new URL(urlString)
        def post = url.openConnection()
        post.method = "POST"
        post.doOutput = true
        post.setConnectTimeout(connectionTimeout)
        post.setReadTimeout(connectionTimeout)
        post.setRequestProperty("Content-Type", "application/json")
        post.setRequestProperty("Authorization", "Bearer ${token}")
        def writer = null, reader = null

        try {
            writer = post.outputStream
            writer.write(content.getBytes("UTF-8"))
            writer.flush()
            def postRC = post.getResponseCode()
            def response = postRC
            if (postRC.equals(200)) {
                reader = post.inputStream
                response = reader.text
            }
            return response
        } finally {
            if (writer != null)
                writer.close()
            if (reader != null)
                reader.close()
        }
    }

    static void reload() {
        def tctx = null
        def tlog = null
        if (p != null) {
            tctx = p.ctx
            tlog = p.log
            p.excutorService.shutdown()
            p = null
        }
        init(tctx, tlog)
    }

    synchronized static void init(ctx, log) {
        if (p == null) {
            p = new SysdigPlugin()
            p.ctx = ctx
            p.log = log
            failedToLoadConfig = false
            try {
                p.loadConfig()
            } catch (ex) {
                failedToLoadConfig = true
                p = null
            }
        }
    }

    private void loadConfig() {
        final String CONFIG_FILE_PATH = "${ctx.artifactoryHome.etcDir}/plugins/SysdigSecurePlugin.properties"
        def inputFile = new File(CONFIG_FILE_PATH)
        def config = new JsonSlurper().parseText(inputFile.text)
        if (config) {
            loadPlugin(config)
            // Debug flag
            if (config.containsKey("debug"))
                p.debug = config.debug == true
            // Timeout
            if (config.containsKey("timeout") && config.timeout > 0 && config.timeout <= MAX_TIMEOUT)
                p.connectionTimeout = config.timeout
            // BaseUrl
            if (config.containsKey("baseurl"))
                p.baseUrl = config.baseurl
        }
    }

    private void loadPlugin(Object cfg) {
        if (cfg.sysdig.url && cfg.sysdig.token) {
            def sysdigDetails = new SysdigEndpointDetails()
            def event = "docker.tagCreated"
            // Async
            if (cfg.containsKey('async'))
                sysdigDetails.async = cfg.async
            // Repositories
            if (cfg.containsKey('repositories'))
                sysdigDetails.repositories = cfg.repositories
            // Path filter
            if (cfg.containsKey('path'))
                sysdigDetails.setPathFilter(cfg.path)
            // Events
            sysdigDetails.url = "${cfg.sysdig.url}/api/scanning/v1/anchore/images"
            sysdigDetails.token = cfg.sysdig.token
            addEvent(event, sysdigDetails)
        }
    }

    private void addEvent(String event, SysdigEndpointDetails sysdigDetails) {
        def eventHooks = p.triggers.get(event)
        if (!eventHooks)
            p.triggers.put(event, [sysdigDetails])
        else
            eventHooks.add(sysdigDetails)
    }

    def findDeep(def tree, String key) {
        Set results = []
        findDeep(tree, key, results)
        return results
    }

    def findDeep(def tree, String key, def results) {
        switch (tree) {
            case Map: tree.findAll { k, v ->
                if (v instanceof Map || v instanceof Collection)
                    findDeep(v, key, results)
                else if (k == key)
                    results.add(v)
            }
            case Collection:  tree.findAll { e ->
                if (e instanceof Map || e instanceof Collection)
                    findDeep(e, key, results)
            }
        }
    }

    class SysdigEndpointDetails {
        public static String ALL_REPOS = "*"
        def url
        def token
        def format = null // Default
        def repositories = [ALL_REPOS] // All
        def async = true
        Pattern path = null
        String tag = null

        boolean allRepos() {
            return repositories.contains(ALL_REPOS)
        }

        boolean appliesToRepo(String repoKey) {
            return allRepos() || repositories.contains(repoKey)
        }

        boolean isAsync() {
            return async
        }

        boolean isDefaultFormat() {
            return format == 'default'
        }

        boolean hasPathFilter() {
            return path != null
        }

        boolean matchesTagFilter(String actualTag) {
            if(tag == null)
                return true
            return tag.equals(actualTag)
        }

        boolean matchesPathFilter(String actualPath) {
            if (path == null)
                return true
            return path.matcher(actualPath).matches()
        }

        void setPathFilter(String searchString) {
            // Remove leading '/'
            if (searchString.startsWith('/'))
                searchString = searchString.substring(1)
            //Set tag if one exists
            def tagIndex = searchString.indexOf(':')
            if (tagIndex >= 0 && tagIndex < searchString.length()-1) {
                tag = searchString.substring(tagIndex+1)
                searchString = searchString.substring(0, tagIndex)
            }
            path = Pattern.compile(regexFilter(searchString))
        }

        private String regexFilter(String searchString) {
            (['\\','.','[',']','{','}','(',')','<','>','+','-','=','?','^','$', '|']).each {
                searchString = searchString.replace(it, '\\' + it)
            }
            searchString = searchString.replace('*', '.*')
            return searchString
        }
    }

    class PostTask implements Runnable {
        private String url
        private String token
        private String content

        PostTask(String url, String token, String content) {
            this.url = url
            this.token = token
            this.content = content
        }

        void run() {
            callPost(url, token, content)
        }
    }
}
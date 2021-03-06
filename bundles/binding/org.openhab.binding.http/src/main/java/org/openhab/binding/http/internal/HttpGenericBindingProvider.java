/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.http.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openhab.binding.http.HttpBindingProvider;
import org.openhab.core.binding.BindingConfig;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.TypeParser;
import org.openhab.model.item.binding.AbstractGenericBindingProvider;
import org.openhab.model.item.binding.BindingConfigParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class can parse information from the generic binding format and
 * provides HTTP binding information from it. It registers as an
 * {@link HttpBindingProvider} service as well.
 *
 * Here are some examples for valid binding configuration strings:
 * <ul>
 * <li>
 * <code>{ http=">[ON:POST:http://www.domain.org/home/lights/23871?status=on] >[OFF:POST:http://www.domain.org/home/lights/23871?status=off]" }</code>
 * </li>
 * <li><code>{ http="<[http://www.domain.org/weather/openhabcity/daily:60000:REGEX(.*)]" }</code></li>
 * <li>
 * <code>{ http=">[ON:POST:http://www.domain.org/home/lights/23871?status=on] >[OFF:POST:http://www.domain.org/home/lights/23871?status=off] <[http://www.domain.org/weather/openhabcity/daily:60000:REGEX(.*)]" }</code>
 * </li>
 * <li><code>{ http=">[*:POST:http://www.domain.org/home/lights/23871?status=%2$s&date=%1$tY-%1$tm-%1$td]" }</code></li>
 * <li>
 * <code>{ http=">[CHANGED:POST:http://www.domain.org/home/lights/23871?status=%2$s&date=%1$tY-%1$tm-%1$td]" }</code>
 * </li>
 * <li>
 * <code>{ http=">[CHANGED:POST:http://www.domain.org/home/lights/23871?status=%2$s&date=%1$tY-%1$tm-%1$td{AuthKey=somekey&timerange=day}]" }</code>
 * </li>
 * <li>
 * <code>{ http="<[https://www.flukso.net/api/sensor/xxxx?interval=daily{X-Token=mytoken&X-version=1.0}:60000:REGEX(.*?<title>(.*?)</title>(.*))]" }</code>
 * </li>
 * </ul>
 *
 * @author Thomas.Eichstaedt-Engelen
 * @author Chris Carman
 *
 * @since 0.6.0
 */
public class HttpGenericBindingProvider extends AbstractGenericBindingProvider implements HttpBindingProvider {

    static final Logger logger = LoggerFactory.getLogger(HttpGenericBindingProvider.class);

    /**
     * Artificial command for the http-in configuration (which has no command
     * part by definition). Because we use this artificial command we can reuse
     * the {@link HttpBindingConfig} for both in- and out-configuration.
     */
    protected static final Command IN_BINDING_KEY = StringType.valueOf("IN_BINDING");

    /**
     * Artificial command to identify that state changes should be taken into account
     */
    protected static final Command CHANGED_COMMAND_KEY = StringType.valueOf("CHANGED");

    protected static final Command WILDCARD_COMMAND_KEY = StringType.valueOf("*");

    /** {@link Pattern} which matches a binding configuration part */
    private static final Pattern BASE_CONFIG_PATTERN = Pattern.compile("(<|>)\\[(.*?)\\](\\s|$)");

    /** {@link Pattern} which matches an In-Binding */
    private static final Pattern IN_BINDING_PATTERN = Pattern.compile("(.*?)(\\{.*\\})?:(?!//)(\\d*):(.*)");

    /** {@link Pattern} which matches an Out-Binding */
    private static final Pattern OUT_BINDING_PATTERN = Pattern.compile("(.*?):([A-Z]*):(.*)");

    /** {@link Pattern} that separates a url string from the following post body string */
    private static final Pattern URL_PARSING_PATTERN = Pattern
            .compile("^((([^:/?#]+):)?(//([^/?#]*))?([^?#:]*)(\\?([^#:]*))?(#(.*))?)(:.*)?");

    /** {@link Pattern} for parsing a transformation */
    private static final Pattern TRANSFORM_PATTERN = Pattern.compile("(.*)\\((.*)\\)");

    /**
     * {@inheritDoc}
     */
    @Override
    public String getBindingType() {
        return "http";
    }

    /**
     * @{inheritDoc}
     */
    @Override
    public void validateItemType(Item item, String bindingConfig) throws BindingConfigParseException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processBindingConfiguration(String context, Item item, String bindingConfig)
            throws BindingConfigParseException {
        super.processBindingConfiguration(context, item, bindingConfig);

        if (bindingConfig != null) {
            HttpBindingConfig config = parseBindingConfig(item, bindingConfig);
            addBindingConfig(item, config);
        } else {
            logger.warn("bindingConfig is NULL (item=" + item + ") -> process bindingConfig aborted!");
        }
    }

    /**
     * Delegates parsing the <code>bindingConfig</code> with respect to the
     * first character (<code>&lt;</code> or <code>&gt;</code>) to the
     * specialized parsing methods
     *
     * @param item
     * @param bindingConfig
     *
     * @throws BindingConfigParseException
     */
    protected HttpBindingConfig parseBindingConfig(Item item, String bindingConfig) throws BindingConfigParseException {

        HttpBindingConfig config = new HttpBindingConfig(item);

        Matcher matcher = BASE_CONFIG_PATTERN.matcher(bindingConfig);

        if (!matcher.matches()) {
            throw new BindingConfigParseException(
                    "bindingConfig '" + bindingConfig + "' doesn't contain a valid binding configuration");
        }
        matcher.reset();

        while (matcher.find()) {
            String direction = matcher.group(1);
            String bindingConfigPart = matcher.group(2);

            if (direction.equals("<")) {
                config = parseInBindingConfig(item, bindingConfigPart, config);
            } else if (direction.equals(">")) {
                config = parseOutBindingConfig(item, bindingConfigPart, config);
            } else {
                throw new BindingConfigParseException(
                        "Unknown command given! Configuration must start with '<' or '>' ");
            }
        }

        return config;
    }

    /**
     * Parses a http-in configuration by using the regular expression
     * <code>(.*?):(\\d*):(.*)</code>. Where the groups should contain the
     * following content:
     * <ul>
     * <li>1 - url</li>
     * <li>2 - refresh interval</li>
     * <li>3 - the transformation rule</li>
     * </ul>
     *
     * @param item
     *
     * @param bindingConfig the config string to parse
     * @param config
     * @return the filled {@link HttpBindingConfig}
     *
     * @throws BindingConfigParseException if the regular expression doesn't match
     *             the given <code>bindingConfig</code>
     */
    protected HttpBindingConfig parseInBindingConfig(Item item, String bindingConfig, HttpBindingConfig config)
            throws BindingConfigParseException {
        Matcher matcher = IN_BINDING_PATTERN.matcher(bindingConfig);

        if (!matcher.matches()) {
            throw new BindingConfigParseException("bindingConfig '" + bindingConfig
                    + "' doesn't represent a valid in-binding-configuration. A valid configuration is matched by the RegExp '"
                    + IN_BINDING_PATTERN + "'");
        }
        matcher.reset();

        HttpBindingConfigElement configElement;

        while (matcher.find()) {
            configElement = new HttpBindingConfigElement();
            configElement.url = matcher.group(1).replaceAll("\\\\\"", "");
            configElement.headers = parseHttpHeaders(matcher.group(2));
            configElement.refreshInterval = Integer.valueOf(matcher.group(3)).intValue();
            configElement.transformation = matcher.group(4).replaceAll("\\\\\"", "\"");
            config.put(IN_BINDING_KEY, configElement);
        }

        return config;
    }

    private Properties parseHttpHeaders(String group) {
        Properties headers = new Properties();
        if (group != null && group.length() > 0) {
            if (group.startsWith("{")) {
                group = group.substring(1);
            }
            if (group.endsWith("}")) {
                group = group.substring(0, group.length() - 1);
            }
            String[] headersArray = group.split("&");
            for (String headerElement : headersArray) {
                int idx = headerElement.indexOf("=");
                if (idx >= 0) {
                    headers.setProperty(headerElement.substring(0, idx), headerElement.substring(idx + 1));
                }
            }
        }
        return headers;
    }

    /**
     * Parses an http-out configuration by using the regular expression
     * <code>(.*?):([A-Z]*):(.*)</code>. Where the groups should contain the
     * following content:
     * <ul>
     * <li>1 - command</li>
     * <li>2 - http method</li>
     * <li>3 - url</li>
     * <li>4 - post body</li>
     * </ul>
     *
     * @param item
     * @param bindingConfig the config string to parse
     * @param config
     * @return the filled {@link HttpBindingConfig}
     *
     * @throws BindingConfigParseException if the regular expression doesn't match
     *             the given <code>bindingConfig</code>
     */
    protected HttpBindingConfig parseOutBindingConfig(Item item, String bindingConfig, HttpBindingConfig config)
            throws BindingConfigParseException {
        logger.debug("parsing this as an http out binding: {}", bindingConfig);
        Matcher matcher = OUT_BINDING_PATTERN.matcher(bindingConfig);

        if (!matcher.matches()) {
            throw new BindingConfigParseException("bindingConfig '" + bindingConfig
                    + "' doesn't contain a valid out-binding-configuration. A valid configuration is matched by the RegExp '"
                    + OUT_BINDING_PATTERN + "'");
        }
        matcher.reset();

        HttpBindingConfigElement configElement;

        while (matcher.find()) {
            configElement = new HttpBindingConfigElement();

            Command command = createCommandFromString(item, matcher.group(1));
            configElement.httpMethod = matcher.group(2);
            String lastPart = matcher.group(3).replaceAll("\\\\\"", "");
            logger.debug("URL portion of binding config to be processed: {}", lastPart);

            Matcher urlMatcher = URL_PARSING_PATTERN.matcher(lastPart);
            urlMatcher.find();
            if (logger.isDebugEnabled()) {
                for (int i = 0; i <= urlMatcher.groupCount(); i++) {
                    logger.debug("Group {}: {}", i, urlMatcher.group(i));
                }
            }

            if (urlMatcher.group(1).endsWith("}")) {
                String g1 = urlMatcher.group(1);
                int beginIdx = g1.indexOf("{");
                int endIdx = g1.indexOf("}");
                configElement.url = g1.substring(0, beginIdx);
                configElement.headers = parseHttpHeaders(g1.substring(beginIdx + 1, endIdx));
            } else {
                configElement.url = urlMatcher.group(1);
            }

            if (configElement.httpMethod.equals("POST") && urlMatcher.group(11) != null) {
                configElement.body = urlMatcher.group(11).substring(1);
                setBodyTransform(configElement, command);
            }

            config.put(command, configElement);
        }

        return config;
    }

    private void setBodyTransform(HttpBindingConfigElement configElement, Command command) {
        if (configElement == null || configElement.body == null) {
            logger.trace("No body content found.");
            return;
        }

        Matcher transformMatcher = TRANSFORM_PATTERN.matcher(configElement.body);
        if (!configElement.body.equals("default") && !transformMatcher.matches()) {
            logger.trace("Body contained no known transforms.");
            return;
        }

        if (configElement.body.equals("default")) {
            configElement.transformation = "default";
        } else {
            String transformCmd = transformMatcher.group(1);
            String transformParam = transformMatcher.group(2);
            logger.trace("transformCmd: {}", transformCmd);
            logger.trace("transformParam: {}", transformParam);
            configElement.transformation = transformCmd + "=" + transformParam;
            configElement.body = null;
        }
    }

    /**
     * Creates a {@link Command} out of the given <code>commandAsString</code>
     * taking the special Commands "CHANGED" and "*" into account and incorporating
     * the {@link TypeParser}.
     *
     * @param item
     * @param commandAsString
     *
     * @return an appropriate Command (see {@link TypeParser} for more
     *         information
     *
     * @throws BindingConfigParseException if the {@link TypeParser} couldn't
     *             create a command appropriately
     *
     * @see {@link TypeParser}
     */
    private Command createCommandFromString(Item item, String commandAsString) throws BindingConfigParseException {

        if (CHANGED_COMMAND_KEY.equals(commandAsString)) {
            return CHANGED_COMMAND_KEY;
        } else if (WILDCARD_COMMAND_KEY.equals(commandAsString)) {
            return WILDCARD_COMMAND_KEY;
        } else {
            Command command = TypeParser.parseCommand(item.getAcceptedCommandTypes(), commandAsString);

            if (command == null) {
                throw new BindingConfigParseException("couldn't create Command from '" + commandAsString + "' ");
            }

            return command;
        }
    }

    /**
     * @{inheritDoc}
     */
    @Override
    public State getState(String itemName, String value) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        if (config != null) {
            List<Class<? extends State>> acceptedDataTypes = config.getAcceptedDataTypes();
            return TypeParser.parseState(acceptedDataTypes, value);
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHttpMethod(String itemName, Command command) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && getConfigElement(config, command) != null
                ? getConfigElement(config, command).httpMethod : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUrl(String itemName, Command command) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && getConfigElement(config, command) != null ? getConfigElement(config, command).url
                : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Properties getHttpHeaders(String itemName, Command command) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && getConfigElement(config, command) != null ? getConfigElement(config, command).headers
                : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUrl(String itemName) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && config.get(IN_BINDING_KEY) != null ? config.get(IN_BINDING_KEY).url : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getBody(String itemName, Command command) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && getConfigElement(config, command) != null ? getConfigElement(config, command).body
                : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Properties getHttpHeaders(String itemName) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && config.get(IN_BINDING_KEY) != null ? config.get(IN_BINDING_KEY).headers : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRefreshInterval(String itemName) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && config.get(IN_BINDING_KEY) != null ? config.get(IN_BINDING_KEY).refreshInterval : 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTransformation(String itemName) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && config.get(IN_BINDING_KEY) != null ? config.get(IN_BINDING_KEY).transformation : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTransformation(String itemName, Command command) {
        HttpBindingConfig config = (HttpBindingConfig) bindingConfigs.get(itemName);
        return config != null && getConfigElement(config, command) != null
                ? getConfigElement(config, command).transformation : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getInBindingItemNames() {
        List<String> inBindings = new ArrayList<String>();
        for (String itemName : bindingConfigs.keySet()) {
            HttpBindingConfig httpConfig = (HttpBindingConfig) bindingConfigs.get(itemName);
            if (httpConfig.containsKey(IN_BINDING_KEY)) {
                inBindings.add(itemName);
            }
        }
        return inBindings;
    }

    private HttpBindingConfigElement getConfigElement(HttpBindingConfig config, Command command) {
        if (config.get(command) != null) {
            return config.get(command);
        }

        if (!CHANGED_COMMAND_KEY.equals(command)) {
            return config.get(WILDCARD_COMMAND_KEY);
        }

        return null;
    }

    /**
     * This is an internal data structure to map commands to
     * {@link HttpBindingConfigElement }. There will be map like
     * <code>ON->HttpBindingConfigElement</code>
     */
    static class HttpBindingConfig extends HashMap<Command, HttpBindingConfigElement> implements BindingConfig {

        /** generated serialVersion UID */
        private static final long serialVersionUID = 6164971643530954095L;

        private List<Class<? extends State>> acceptedDataTypes = null;

        HttpBindingConfig(Item item) {
            acceptedDataTypes = new ArrayList<Class<? extends State>>(item.getAcceptedDataTypes());
        }

        List<Class<? extends State>> getAcceptedDataTypes() {
            return acceptedDataTypes;
        }
    }

    /**
     * This is an internal data structure to store information from the binding
     * config strings and use it to answer the requests to the HTTP binding
     * provider.
     */
    static class HttpBindingConfigElement implements BindingConfig {

        public String httpMethod;
        public String url;
        public Properties headers;
        public int refreshInterval;
        public String transformation;
        public String body;

        @Override
        public String toString() {
            return "HttpBindingConfigElement [httpMethod=" + httpMethod + ", url=" + url + ", headers=" + headers
                    + ", refreshInterval=" + refreshInterval + ", transformation=" + transformation + "]";
        }

    }

}

package org.bonitasoft.web.rest.server.api.extension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.bonitasoft.console.common.server.page.PageMappingService;
import org.bonitasoft.console.common.server.page.PageReference;
import org.bonitasoft.console.common.server.page.PageResourceProvider;
import org.bonitasoft.engine.exception.BonitaException;
import org.bonitasoft.engine.exception.NotFoundException;
import org.bonitasoft.engine.session.APISession;
import org.restlet.Request;
import org.restlet.ext.servlet.ServletUtils;

/**
 * @author Laurent Leseigneur
 */
public class ResourceExtensionResolver {
    public static final String MAPPING_KEY_SEPARATOR = "|";
    public static final String MAPPING_KEY_PREFIX = "apiExtension";
    public static final String API_EXTENSION_TEMPLATE_PREFIX = "/API/extension/";
    private final Request request;
    private final PageMappingService pageMappingService;

    public ResourceExtensionResolver(Request request, PageMappingService pageMappingService) {
        this.request = request;
        this.pageMappingService = pageMappingService;
    }

    public Long resolvePageId(APISession apiSession) throws BonitaException {
        final HttpServletRequest httpServletRequest = getHttpServletRequest();
        final PageReference pageReference;
        pageReference = pageMappingService.getPage(httpServletRequest, apiSession, generateMappingKey(), httpServletRequest.getLocale());
        return pageReference.getPageId();
    }

    protected HttpServletRequest getHttpServletRequest() {
        return ServletUtils.getRequest(this.request);
    }

    public String generateMappingKey() {
        StringBuilder builder = new StringBuilder();
        final String requestAsString = request.getResourceRef().toString();
        final String pathTemplate = StringUtils.substringAfter(requestAsString, API_EXTENSION_TEMPLATE_PREFIX);

        builder.append(MAPPING_KEY_PREFIX)
                .append(MAPPING_KEY_SEPARATOR)
                .append(request.getMethod().getName())
                .append(MAPPING_KEY_SEPARATOR)
                .append(pathTemplate);

        return builder.toString();
    }

    public String resolveClassFileName(PageResourceProvider pageResourceProvider) throws NotFoundException {
        final Properties properties = new Properties();

        try {
            final InputStream resourceAsStream = pageResourceProvider.getResourceAsStream("page.properties");
            properties.load(resourceAsStream);
        } catch (IOException e) {
            throw new NotFoundException("error while getting resource:" + generateMappingKey());
        }

        final String apiExtensionList = (String) properties.get("apiExtensions");
        final String[] apiExtensions = apiExtensionList.split(",");

        return findMatchingExtension(properties, apiExtensions);

    }

    private String findMatchingExtension(Properties properties, String[] apiExtensions) throws NotFoundException {
        for (String apiExtension : apiExtensions) {
            final String method = (String) properties.get(String.format("%s.method", apiExtension.trim()));
            final String pathTemplate = (String) properties.get(String.format("%s.pathTemplate", apiExtension.trim()));
            final String classFileName = (String) properties.get(String.format("%s.classFileName", apiExtension.trim()));

            if (extensionMatches(method, pathTemplate)) {
                return classFileName;
            }
        }
        throw new NotFoundException("error while getting resource:" + generateMappingKey());
    }

    private boolean extensionMatches(String method, String pathTemplate) {
        return request.getMethod().getName().equals(method) && request.getResourceRef().toString().contains(String.format("%s%s", API_EXTENSION_TEMPLATE_PREFIX, pathTemplate));
    }
}

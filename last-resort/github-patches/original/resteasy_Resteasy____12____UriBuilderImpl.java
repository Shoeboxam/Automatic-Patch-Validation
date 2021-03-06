package org.jboss.resteasy.specimpl;

import org.jboss.resteasy.util.Encode;
import org.jboss.resteasy.util.PathHelper;

import javax.ws.rs.Path;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class UriBuilderImpl extends UriBuilder
{

   private String host;
   private String scheme;
   private int port = -1;

   private String userInfo;
   private String path;
   private String query;
   private String fragment;


   public UriBuilder clone()
   {
      UriBuilderImpl impl = new UriBuilderImpl();
      impl.host = host;
      impl.scheme = scheme;
      impl.port = port;
      impl.userInfo = userInfo;
      impl.path = path;
      impl.query = query;
      impl.fragment = fragment;

      return impl;
   }

   @Override
   public UriBuilder uri(URI uri) throws IllegalArgumentException
   {
      if (uri.getHost() != null) host = uri.getHost();
      if (uri.getScheme() != null) scheme = uri.getScheme();
      if (uri.getHost() != null) port = uri.getPort();
      if (uri.getUserInfo() != null) userInfo = uri.getRawUserInfo();
      if (uri.getPath() != null && !uri.getPath().equals("")) path = uri.getRawPath();
      if (uri.getFragment() != null) fragment = uri.getRawFragment();
      if (uri.getQuery() != null) query = uri.getRawQuery();
      return this;
   }

   @Override
   public UriBuilder scheme(String scheme) throws IllegalArgumentException
   {
      this.scheme = scheme;
      return this;
   }

   @Override
   public UriBuilder schemeSpecificPart(String ssp) throws IllegalArgumentException
   {
      StringBuffer uriStr = new StringBuffer();
      if (scheme != null) uriStr.append(scheme).append(":");
      uriStr.append(ssp);
      if (fragment != null)
      {
         uriStr.append("#").append(fragment);
      }
      return uri(URI.create(uriStr.toString()));
   }

   @Override
   public UriBuilder userInfo(String ui) throws IllegalArgumentException
   {
      this.userInfo = ui;
      return this;
   }

   @Override
   public UriBuilder host(String host) throws IllegalArgumentException
   {
      this.host = host;
      return this;
   }

   @Override
   public UriBuilder port(int port) throws IllegalArgumentException
   {
      this.port = port;
      return this;
   }

   protected static String paths(boolean encode, String basePath, String... segments)
   {
      String path = basePath;
      if (path == null) path = "";
      for (String segment : segments)
      {
         if ("".equals(segment)) continue;
         if (!path.endsWith("/")) path += "/";
         if (segment.equals("/")) continue;
         if (segment.startsWith("/")) segment = segment.substring(1);
         if (encode) segment = Encode.encodePath(segment, true);
         path += segment;

      }
      return path;
   }

   @Override
   public UriBuilder path(String segment) throws IllegalArgumentException
   {
      path = paths(true, path, segment);
      return this;
   }

   @Override
   public UriBuilder path(Class resource) throws IllegalArgumentException
   {
      Path ann = (Path) resource.getAnnotation(Path.class);
      if (ann != null)
      {
         String[] segments = new String[]{ann.value()};
         path = paths(true, path, segments);
      }
      return this;
   }

   @Override
   public UriBuilder path(Class resource, String method) throws IllegalArgumentException
   {
      for (Method m : resource.getMethods())
      {
         if (m.getName().equals(method))
         {
            return path(m);
         }
      }
      return this;
   }

   @Override
   public UriBuilder path(Method method) throws IllegalArgumentException
   {
      Path ann = method.getAnnotation(Path.class);
      if (ann != null)
      {
         String[] segments = new String[]{ann.value()};
         path = paths(true, path, segments);
      }
      return this;
   }

   @Override
   public UriBuilder replaceMatrix(String matrix) throws IllegalArgumentException
   {

      if (!matrix.startsWith(";")) matrix = ";" + matrix;
      if (path == null)
      {
         path = matrix;
      }
      else
      {
         int start = path.lastIndexOf('/');
         if (start < 0) start = 0;
         int matrixIndex = path.indexOf(';', start);
         if (matrixIndex > -1) path = path.substring(0, matrixIndex) + matrix;
         else path += matrix;

      }
      return this;
   }

   @Override
   public UriBuilder replaceQuery(String query) throws IllegalArgumentException
   {
      this.query = query;
      return this;
   }

   @Override
   public UriBuilder fragment(String fragment) throws IllegalArgumentException
   {
      this.fragment = Encode.encodeSegment(fragment, true);
      return this;
   }

   /**
    * Only replace path params in path of URI.  This changes state of URIBuilder.
    *
    * @param name
    * @param value
    * @param isEncoded
    * @return
    */
   public UriBuilder substitutePathParam(String name, Object value, boolean isEncoded)
   {
      if (path != null)
      {
         StringBuffer buffer = new StringBuffer();
         replaceParameter(name, value.toString(), isEncoded, path, buffer);
         path = buffer.toString();
      }
      return this;
   }

   @Override
   public URI buildFromMap(Map<String, ? extends Object> values) throws IllegalArgumentException, UriBuilderException
   {
      return buildFromMap(values, false);
   }

   @Override
   public URI buildFromEncodedMap(Map<String, ? extends Object> values) throws IllegalArgumentException, UriBuilderException
   {
      return buildFromMap(values, true);
   }

   public URI buildFromMap(Map<String, ? extends Object> paramMap, boolean isEncoded) throws IllegalArgumentException, UriBuilderException
   {
      StringBuffer buffer = new StringBuffer();

      if (scheme != null) replaceParameter(paramMap, isEncoded, scheme, buffer).append("://");
      if (userInfo != null) replaceParameter(paramMap, isEncoded, userInfo, buffer).append("@");
      if (host != null) replaceParameter(paramMap, isEncoded, host, buffer);
      if (port != -1 && port != 80) buffer.append(":").append(Integer.toString(port));
      if (path != null) replaceParameter(paramMap, isEncoded, path, buffer);
      if (query != null)
      {
         buffer.append("?");
         replaceParameter(paramMap, isEncoded, query, buffer);
      }
      if (fragment != null)
      {
         buffer.append("#");
         replaceParameter(paramMap, isEncoded, fragment, buffer);
      }
      String buf = buffer.toString();
      try
      {
         return URI.create(buf);
      }
      catch (Exception e)
      {
         throw new RuntimeException("Failed to create URI: " + buf, e);
      }
   }

   protected StringBuffer replaceParameter(String name, String value, boolean isEncoded, String string, StringBuffer buffer)
   {
      Matcher matcher = PathHelper.URI_PARAM_PATTERN.matcher(string);
      while (matcher.find())
      {
         String param = matcher.group(1);
         if (!param.equals(name)) continue;
         value = Encode.encodeSegment(value, false);
         if (!isEncoded) value = value.replace("%", "%25");
         else Encode.encodeNonCodes(value);
         matcher.appendReplacement(buffer, value);
      }
      matcher.appendTail(buffer);
      return buffer;
   }

   protected StringBuffer replaceParameter(Map<String, ? extends Object> paramMap, boolean isEncoded, String string, StringBuffer buffer)
   {
      Matcher matcher = PathHelper.URI_PARAM_PATTERN.matcher(string);
      while (matcher.find())
      {
         String param = matcher.group(1);
         String value = paramMap.get(param).toString();
         if (value != null)
         {
            value = Encode.encodeSegment(value, false);
            if (!isEncoded) value = value.replace("%", "%25");
            else Encode.encodeNonCodes(value);
            matcher.appendReplacement(buffer, value);
         }
         else
         {
            throw new IllegalArgumentException("path param " + param + " has not been provided by the parameter map");
         }
      }
      matcher.appendTail(buffer);
      return buffer;
   }

   /**
    * Return a unique order list of path params
    *
    * @return
    */
   protected List<String> getPathParamNamesInDeclarationOrder()
   {
      List<String> params = new ArrayList<String>();
      HashSet<String> set = new HashSet<String>();
      if (scheme != null) addToPathParamList(params, set, scheme);
      if (userInfo != null) addToPathParamList(params, set, userInfo);
      if (host != null) addToPathParamList(params, set, host);
      if (path != null) addToPathParamList(params, set, path);
      if (query != null) addToPathParamList(params, set, query);
      if (fragment != null) addToPathParamList(params, set, fragment);

      return params;
   }

   private void addToPathParamList(List<String> params, HashSet<String> set, String string)
   {
      Matcher matcher = PathHelper.URI_PARAM_PATTERN.matcher(string);
      while (matcher.find())
      {
         String param = matcher.group(1);
         if (set.contains(param)) continue;
         else
         {
            set.add(param);
            params.add(param);
         }
      }
   }

   @Override
   public URI build(Object... values) throws IllegalArgumentException, UriBuilderException
   {
      return buildFromValues(false, values);
   }

   protected URI buildFromValues(boolean encoded, Object... values)
   {
      List<String> params = getPathParamNamesInDeclarationOrder();
      if (values.length < params.size())
         throw new IllegalArgumentException("You did not supply enough values to fill path parameters");
      if (values.length > params.size()) throw new IllegalArgumentException("You provided too many values");

      Map<String, Object> pathParams = new HashMap<String, Object>();

      int i = 0;

      for (Object val : values)
      {
         if (val == null) throw new IllegalArgumentException("A value was null");
         String pathParam = params.get(i++);
         pathParams.put(pathParam, val.toString());
      }
      return buildFromMap(pathParams, encoded);
   }

   @Override
   public UriBuilder matrixParam(String name, Object... values) throws IllegalArgumentException
   {
      if (path == null) path = "";
      for (Object val : values)
      {
         path += ";" + Encode.encodeSegment(name, false) + "=" + Encode.encodeSegment(val.toString(), true);
      }
      return this;
   }

   private static final Pattern PARAM_REPLACEMENT = Pattern.compile("_resteasy_uri_parameter");

   @Override
   public UriBuilder replaceMatrixParam(String name, Object... values) throws IllegalArgumentException
   {
      if (path == null) return matrixParam(name, values);

      // remove all path param expressions so we don't accidentally start replacing within a regular expression
      ArrayList<String> pathParams = new ArrayList<String>();
      boolean foundParam = false;

      Matcher matcher = PathHelper.URI_TEMPLATE_PATTERN.matcher(path);
      StringBuffer newSegment = new StringBuffer();
      while (matcher.find())
      {
         foundParam = true;
         String group = matcher.group();
         pathParams.add(group);
         matcher.appendReplacement(newSegment, "_resteasy_uri_parameter");
      }
      matcher.appendTail(newSegment);
      path = newSegment.toString();

      // Find last path segment
      int start = path.lastIndexOf('/');
      if (start < 0) start = 0;

      int matrixIndex = path.indexOf(';', start);
      if (matrixIndex > -1)
      {

         String matrixParams = path.substring(matrixIndex + 1);
         path = path.substring(0, matrixIndex);
         MultivaluedMapImpl<String, String> map = new MultivaluedMapImpl<String, String>();

         String[] params = matrixParams.split(";");
         for (String param : params)
         {
            String[] namevalue = param.split("=");
            if (namevalue != null && namevalue.length > 0)
            {
               String theName = namevalue[0];
               String value = "";
               if (namevalue.length > 1)
               {
                  value = namevalue[1];
               }
               map.add(theName, value);
            }
         }
         map.remove(name);
         for (String theName : map.keySet())
         {
            List<String> vals = map.get(theName);
            for (Object val : vals)
            {
               path += ";" + name + "=" + val.toString();
            }
         }
      }
      matrixParam(name, values);

      // put back all path param expressions
      if (foundParam)
      {
         matcher = PARAM_REPLACEMENT.matcher(path);
         newSegment = new StringBuffer();
         int i = 0;
         while (matcher.find())
         {
            matcher.appendReplacement(newSegment, pathParams.get(i++));
         }
         matcher.appendTail(newSegment);
         path = newSegment.toString();
      }
      return this;
   }

   @Override
   public UriBuilder queryParam(String name, Object... values) throws IllegalArgumentException
   {
      for (Object value : values)
      {
         if (query == null) query = "";
         else query += "&";
         query += Encode.encodeSegment(name, false) + "=" + Encode.encodeSegment(value.toString(), true);
      }
      return this;
   }

   @Override
   public UriBuilder replaceQueryParam(String name, Object... values) throws IllegalArgumentException
   {
      if (query == null || query.equals("")) return queryParam(name, values);

      String[] params = query.split("&");
      query = null;

      String replacedName = Encode.encodeSegment(name, false);

      for (String param : params)
      {
         if (param.indexOf('=') >= 0)
         {
            String[] nv = param.split("=");
            String paramName = nv[0];
            if (paramName.equals(replacedName)) continue;

            if (query == null) query = "";
            else query += "&";
            query += nv[0] + "=" + nv[1];
         }
         else
         {
            if (param.equals(replacedName)) continue;

            if (query == null) query = "";
            else query += "&";
            query += param;
         }
      }
      return queryParam(name, values);
   }

   public String getHost()
   {
      return host;
   }

   public String getScheme()
   {
      return scheme;
   }

   public int getPort()
   {
      return port;
   }

   public String getUserInfo()
   {
      return userInfo;
   }

   public String getPath()
   {
      return path;
   }

   public String getQuery()
   {
      return query;
   }

   public String getFragment()
   {
      return fragment;
   }

   @Override
   public UriBuilder segment(String... segments) throws IllegalArgumentException
   {
      for (String segment : segments)
      {
         path(Encode.encodeSegment(segment, true));
      }
      return this;
   }

   @Override
   public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException
   {
      return buildFromValues(true, values);
   }

   @Override
   public UriBuilder replacePath(String path)
   {
      this.path = Encode.encodePath(path, true);
      return this;
   }

}

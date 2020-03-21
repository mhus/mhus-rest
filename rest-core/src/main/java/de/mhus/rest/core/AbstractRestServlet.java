package de.mhus.rest.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.apache.shiro.web.subject.WebSubject;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;

import de.mhus.lib.core.MApi;
import de.mhus.lib.core.MConstants;
import de.mhus.lib.core.MProperties;
import de.mhus.lib.core.io.http.MHttp;
import de.mhus.lib.core.logging.LevelMapper;
import de.mhus.lib.core.logging.Log;
import de.mhus.lib.core.logging.MLogUtil;
import de.mhus.lib.core.logging.TrailLevelMapper;
import de.mhus.rest.core.api.Node;
import de.mhus.rest.core.api.RestApi;
import de.mhus.rest.core.api.RestException;
import de.mhus.rest.core.api.RestResult;

public abstract class AbstractRestServlet extends HttpServlet {

    private static final String RESULT_TYPE_JSON = "json";
    private static final String RESULT_TYPE_HTTP = "http";

    private static final String PUBLIC_PATH = "/public/";

    private Log log = Log.getLog(this);

    private static final long serialVersionUID = 1L;

    private int nextId = 0;
    private LinkedList<RestAuthenticator> authenticators = new LinkedList<>();
    private WebSecurityManager securityManager;
    private RestApi restService;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // System.out.println(">>> " + req.getPathInfo());
        resp.setHeader("Access-Control-Allow-Origin", "*");

        boolean isTrailEnabled = false;
        try {
            String trail = req.getParameter(MConstants.LOG_MAPPER);
            if (trail != null) {
                LevelMapper lm = MApi.get().getLogFactory().getLevelMapper();
                if (lm != null && lm instanceof TrailLevelMapper) {
                    isTrailEnabled = true;
                    if (trail.length() == 0) trail = MLogUtil.MAP_LABEL;
                    ((TrailLevelMapper) lm).doConfigureTrail(MLogUtil.TRAIL_SOURCE_REST, trail);
                }
            }

            final String path = req.getPathInfo();

            if (path == null || path.length() < 1) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // authenticate
            AuthenticationToken token = null;
            for (RestAuthenticator authenticator : authenticators) {
                token = authenticator.authenticate(req);
                if (token != null) break;
            }
            
            // create shiro Subject and execute
            final AuthenticationToken finalToken = token;
            WebSubject subject = createSubject(req, resp);
            subject.execute(() -> serviceInSession(req, resp, path, finalToken));


        } finally {
            if (isTrailEnabled) {
                LevelMapper lm = MApi.get().getLogFactory().getLevelMapper();
                if (lm != null && lm instanceof TrailLevelMapper)
                    ((TrailLevelMapper) lm).doResetTrail();
            }
        }
    }

    private Object serviceInSession(HttpServletRequest req, HttpServletResponse resp, String path, AuthenticationToken authToken) throws IOException {

        // id
        long id = newId();
        // subject
        Subject subject = SecurityUtils.getSubject();
        // method
        String method = req.getParameter("_method");
        if (method == null) method = req.getMethod();
        // parts of path
        List<String> parts = new LinkedList<String>(Arrays.asList(path.split("/")));
        if (parts.size() == 0) return null;
        parts.remove(0); // [empty]
        //      parts.remove(0); // rest
        // context
        MProperties context = new MProperties();
        // log access
        logAccess(
                id,
                req.getRemoteAddr(),
                req.getRemotePort(),
                subject,
                method,
                req.getPathInfo(),
                req.getParameterMap());

        // authenticate
        if (authToken != null) {
            try {
                subject.login(authToken);
            } catch (AuthenticationException e) {
                return onLoginFailure(authToken, e, req, resp, id);
            }
        }
        
        // check for public access
        if (authToken == null && !isPublicPath(path)) {
            return onLoginFailure(req, resp, id);
        }
        
        // create call context object
        CallContext callContext =
                new CallContext(
                        new HttpRequest(req.getParameterMap()),
                        MHttp.toMethod(method),
                        context);

        RestApi restService = getRestService(); 

        RestResult res = null;

        if (method.equals(MHttp.METHOD_HEAD)) {
            // nothing more to do
            return null;
        }

        try {
            Node item = restService.lookup(parts, null, callContext);

            if (item == null) {
                sendError(
                        id,
                        req,
                        resp,
                        HttpServletResponse.SC_NOT_FOUND,
                        "Resource Not Found",
                        null,
                        subject);
                return null;
            }

            if (method.equals(MHttp.METHOD_GET)) {
                res = item.doRead(callContext);
            } else if (method.equals(MHttp.METHOD_POST)) {

                if (callContext.hasAction()) res = item.doAction(callContext);
                else res = item.doCreate(callContext);
            } else if (method.equals(MHttp.METHOD_PUT)) {
                res = item.doUpdate(callContext);
            } else if (method.equals(MHttp.METHOD_DELETE)) {
                res = item.doDelete(callContext);
            } else if (method.equals(MHttp.METHOD_TRACE)) {

            }

            if (res == null) {
                sendError(
                        id,
                        req,
                        resp,
                        HttpServletResponse.SC_NOT_IMPLEMENTED,
                        null,
                        null,
                        subject);
                return null;
            }

            try {
                if (res != null) {
                    log.d("result", id, res);
                    resp.setContentType(res.getContentType());
                    res.write(resp.getWriter());
                }
            } catch (Throwable t) {
                log.d(t);
                sendError(
                        id,
                        req,
                        resp,
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        t.getMessage(),
                        t,
                        subject);
                return null;
            }

        } catch (RestException t) {
            log.d(t);
            sendError(
                    id,
                    req,
                    resp,
                    t.getErrorId(),
                    t.getMessage(),
                    t,
                    subject);
            return null;
        } catch (Throwable t) {
            log.d(t);
            sendError(
                    id,
                    req,
                    resp,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    t.getMessage(),
                    t,
                    subject);
            return null;
        }
        return null;
    }

    public boolean isPublicPath(String path) {
        return path.startsWith(PUBLIC_PATH);
    }

    public RestApi getRestService() {
        return restService;
    }

    public void setRestService(RestApi service) {
        this.restService = service;
    }

    private Object onLoginFailure(AuthenticationToken authToken, AuthenticationException e, HttpServletRequest req,
            HttpServletResponse resp, long id) throws IOException {

        resp.setHeader("WWW-Authenticate", "BASIC realm=\"rest\"");
        sendError(
                id,
                req,
                resp,
                HttpServletResponse.SC_UNAUTHORIZED,
                e.getMessage(),
                e,
                null);
        return null;
    }

    private Object onLoginFailure(HttpServletRequest req,
            HttpServletResponse resp, long id) throws IOException {

        resp.setHeader("WWW-Authenticate", "BASIC realm=\"rest\"");
        sendError(
                id,
                req,
                resp,
                HttpServletResponse.SC_UNAUTHORIZED,
                "",
                null,
                null);
        return null;
    }
    
    protected void updateSessionLastAccessTime(ServletRequest request, ServletResponse response) {
        if (!isHttpSessions()) { //'native' sessions
            Subject subject = SecurityUtils.getSubject();
            //Subject should never _ever_ be null, but just in case:
            if (subject != null) {
                Session session = subject.getSession(false);
                if (session != null) {
                    try {
                        session.touch();
                    } catch (Throwable t) {
                        log.e("session.touch() method invocation has failed.  Unable to update " +
                                "the corresponding session's last access time based on the incoming request.", t);
                    }
                }
            }
        }
    }
    
    protected boolean isHttpSessions() {
        return getSecurityManager().isHttpSessionMode();
    }
    
    protected WebSubject createSubject(ServletRequest request, ServletResponse response) {
        return new WebSubject.Builder(getSecurityManager(), request, response).buildWebSubject();
    }
    
    public WebSecurityManager getSecurityManager() {
        if (securityManager == null)
            securityManager = createSecuritymanager();
        return securityManager;
    }

    protected WebSecurityManager createSecuritymanager() {
        return new DefaultWebSecurityManager();
    }

    public void setSecurityManager(WebSecurityManager sm) {
        this.securityManager = sm;
    }
    
    private void logAccess(
            long id,
            String remoteAddr,
            int remotePort,
            Subject subject,
            String method,
            String pathInfo,
            @SuppressWarnings("rawtypes") Map parameterMap) {

        String paramLog = getParameterLog(parameterMap);
        log.d(
                "access",
                id,
                "\n Remote: "
                        + remoteAddr
                        + ":"
                        + remotePort
                        + "\n Subject: "
                        + subject
                        + "\n Method: "
                        + method
                        + "\n Request: "
                        + pathInfo
                        + "\n Parameters: "
                        + paramLog
                        + "\n");
    }

    private String getParameterLog(Map<?, ?> parameterMap) {
        StringBuilder out = new StringBuilder().append('{');
        for (Map.Entry<?, ?> entry : parameterMap.entrySet()) {
            out.append('\n').append(entry.getKey()).append("=[");
            Object val = entry.getValue();
            if (val == null) {
            } else if (val.getClass().isArray()) {
                boolean first = true;
                Object[] arr = (Object[]) val;
                for (Object o : arr) {
                    if (first) first = false;
                    else out.append(',');
                    out.append(o);
                }
            } else {
                out.append(val);
            }
            out.append("] ");
        }
        out.append('}');
        return out.toString();
    }

    private synchronized long newId() {
        return nextId++;
    }

    private void sendError(
            long id,
            HttpServletRequest req,
            HttpServletResponse resp,
            int errNr,
            String errMsg,
            Throwable t,
            Subject user)
            throws IOException {

        log.d("error", id, errNr, errMsg, t);

        // error result type
        String errorResultType = req.getParameter("_errorResult");
        if (errorResultType == null) errorResultType = RESULT_TYPE_JSON;

        if (errorResultType.equals(RESULT_TYPE_HTTP)) {
            resp.sendError(errNr);
            resp.getWriter().print(errMsg);
            return;
        }

        if (errorResultType.equals(RESULT_TYPE_JSON)) {

            if (errNr == HttpServletResponse.SC_UNAUTHORIZED) resp.setStatus(errNr);
            else resp.setStatus(HttpServletResponse.SC_OK);

            PrintWriter w = resp.getWriter();
            ObjectMapper m = new ObjectMapper();

            ObjectNode json = m.createObjectNode();
            json.put("_sequence", id);
            if (user != null) json.put("_user", String.valueOf(user.getPrincipal()));
            LevelMapper lm = MApi.get().getLogFactory().getLevelMapper();
            if (lm != null && lm instanceof TrailLevelMapper)
                json.put("_trail", ((TrailLevelMapper) lm).getTrailId());
            json.put("_error", errNr);
            json.put("_errorMessage", errMsg);
            resp.setContentType("application/json");
            m.writeValue(w, json);

            return;
        }
    }

    public LinkedList<RestAuthenticator> getAuthenticators() {
        return authenticators;
    }

}
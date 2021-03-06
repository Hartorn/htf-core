package org.hartorn.htf.handler.path;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hartorn.htf.annotation.AnnotationHelper;
import org.hartorn.htf.annotation.FromUrl;
import org.hartorn.htf.annotation.HtfController;
import org.hartorn.htf.annotation.HtfMethod;
import org.hartorn.htf.annotation.HttpVerbs;
import org.hartorn.htf.exception.ImplementationException;
import org.hartorn.htf.handler.request.HtfResponse;
import org.hartorn.htf.util.Pair;
import org.hartorn.htf.util.StringUtil;

/**
 * Class use to resolve the url path for a request, and try to identify the controller and method to call.
 *
 * @author Hartorn
 *
 */
public final class UrlResolver {
    private static final char SLASH_CHAR = '/';
    private static final Logger LOG = LogManager.getLogger();

    private final HtfTree<ControllerData> tree;
    private final Map<Class<?>, Object> instanceMap;

    /**
     * Constructor. Build the tree used for resolving url.
     *
     * @param controllers
     *            Set of controllers class.
     * @throws ImplementationException
     *             System exception
     */
    public UrlResolver(final Set<Class<?>> controllers) throws ImplementationException {
        this.instanceMap = new ConcurrentHashMap<Class<?>, Object>();
        this.tree = new GenericTree<ControllerData>(UrlResolver.SLASH_CHAR);
        this.initialiseUrlTree(controllers);
    }

    /**
     * Method giving the url after the context path.
     *
     * @param request
     *            the http request
     * @return the part of the url after the context path
     */
    public static String getControllerUrl(final HttpServletRequest request) {
        return request.getRequestURI().replaceFirst(Matcher.quoteReplacement(request.getContextPath()), StringUtil.EMPTY);
    }

    /**
     * Resolve the url of the request, and return a pair of the controller, and the method to be invoked.
     *
     * @param request
     *            the HttpRequest
     * @param verb
     *            the HttpVErb
     * @return a Pair of controller, and method
     * @throws ImplementationException
     *             Exception thrown
     */
    public Pair<Object, Method> resolveRequest(final HttpServletRequest request, final HttpVerbs verb) throws ImplementationException {
        UrlResolver.LOG.debug("HTF - Resolving the url from [{}] with HttpVerb [{}]", request.getRequestURL(), verb.name());

        return this.resolveUrl(StringUtil.stripAndToLower(UrlResolver.getControllerUrl(request), UrlResolver.SLASH_CHAR), verb);
    }

    private String buildUrl(final String ctrlPart, final String methodPart) {
        final StringBuilder url = new StringBuilder();
        url.append(StringUtil.stripAndToLower(ctrlPart, UrlResolver.SLASH_CHAR));
        url.append(UrlResolver.SLASH_CHAR);
        url.append(StringUtil.stripAndToLower(methodPart, UrlResolver.SLASH_CHAR));

        return url.toString();
    }

    private void checkMethodReturnType(final Method method) throws ImplementationException {
        final Class<?> rtnClass = method.getReturnType();
        if (HtfResponse.class.equals(rtnClass)) {
            return;
        }
        final Class<?>[] iClasses = rtnClass.getInterfaces();
        for (final Class<?> iClass : iClasses) {
            if (iClass.equals(HtfResponse.class)) {
                return;
            }
        }
        throw new ImplementationException("Method:" + method.getName() + " Return type:" + rtnClass.getCanonicalName() + " does not implement "
                + HtfResponse.class.getCanonicalName());
    }

    private Pair<Class<?>, Method> checkUrlResolution(final Pair<ControllerData, String[]> dataAndUrlLeftOvers, final HttpVerbs verb)
            throws ImplementationException {
        final String[] leftUrl = dataAndUrlLeftOvers.right();
        final ControllerData ctrlData = dataAndUrlLeftOvers.left();
        Pair<Class<?>, Method> result;
        if ((ctrlData != null) && (ctrlData.getData() != null)) {
            result = ctrlData.getControllerMethodPair(verb);
        } else {
            throw new ImplementationException("HTF - ControllerData with null attribute");
        }
        // Checking if method can be resolved with the given verb
        if (result == null) {
            throw new ImplementationException("HTF - Method cannot be invoked with given verb");
        }
        // Checking if url leftovers are due to UrlParams, or bad resolutions
        final int nbAnnotations = AnnotationHelper.getNumberOfAnnotatedParameters(result.right(), FromUrl.class);
        if (nbAnnotations != leftUrl.length) {
            throw new ImplementationException("HTF - Cannot resolve url");
        }
        return result;
    }

    private Object getControllerInstance(final Class<?> controllerClass) throws ImplementationException {
        Object controllerInstance = this.instanceMap.get(controllerClass);
        if (controllerInstance == null) {
            controllerInstance = this.newInstanceOfController(controllerClass);
            this.instanceMap.put(controllerClass, controllerInstance);
        }
        return controllerInstance;
    }

    private void initialiseUrlTree(final Set<Class<?>> controllers) throws ImplementationException {
        for (final Class<?> controller : controllers) {
            this.registerController(controller);
        }
    }

    private Object newInstanceOfController(final Class<?> controllerClass) throws ImplementationException {
        try {
            return controllerClass.newInstance();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new ImplementationException(controllerClass.getCanonicalName() + " throws a exception on new instance creation", e);
        }
    }

    private void registerController(final Class<?> ctrlClass) throws ImplementationException {
        AnnotationHelper.checkAnnotation(ctrlClass, HtfController.class);
        final HtfController ctrlAnnotation = ctrlClass.getAnnotation(HtfController.class);
        this.registerControllerMethods(ctrlClass, ctrlAnnotation);
    }

    private void registerControllerMethod(final Class<?> ctrlClass, final HtfController ctrlAnnotation, final Pair<Method, HtfMethod> methodInfo)
            throws ImplementationException {
        final HtfMethod methodAnnotation = methodInfo.right();
        final Method method = methodInfo.left();
        // Check the method return type
        this.checkMethodReturnType(method);
        // Build the full url
        final String url = this.buildUrl(ctrlAnnotation.address(), methodAnnotation.address());

        // Register the node
        this.tree.registerData(url, new ControllerData(ctrlClass, method, methodAnnotation.httpVerbs()));
    }

    private void registerControllerMethods(final Class<?> ctrlClass, final HtfController ctrlAnnotation) throws ImplementationException {
        final List<Pair<Method, HtfMethod>> methodsInfos = AnnotationHelper.getAnnotatedMethodsAndAnnotations(ctrlClass, HtfMethod.class);
        for (final Pair<Method, HtfMethod> methodInfo : methodsInfos) {
            this.registerControllerMethod(ctrlClass, ctrlAnnotation, methodInfo);
        }
    }

    private Pair<Object, Method> resolveUrl(final String fullUrl, final HttpVerbs verb) throws ImplementationException {
        final Pair<Class<?>, Method> result = this.checkUrlResolution(this.tree.tryToResolve(fullUrl), verb);

        return Pair.of(this.getControllerInstance(result.left()), result.right());
    }
}

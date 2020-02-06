package uniresolver.web.servlet;

import java.io.IOException;
import java.net.URLDecoder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uniresolver.result.ResolveResult;
import uniresolver.web.WebUniResolver;

public class ResolveServlet extends WebUniResolver {

	private static final long serialVersionUID = 1579362184113490816L;

	protected static Logger log = LoggerFactory.getLogger(WebUniResolver.class);

	public static final String MIME_TYPE = "application/json";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		// read request

		request.setCharacterEncoding("UTF-8");
		response.setCharacterEncoding("UTF-8");

		String contextPath = request.getContextPath();
		String servletPath = request.getServletPath();
		String requestPath = request.getRequestURI();

		String identifier = requestPath.substring(contextPath.length() + servletPath.length());
		if (identifier.startsWith("/")) identifier = identifier.substring(1);

		try {

			identifier = URLDecoder.decode(identifier, "UTF-8");
		} catch (Exception ex) {

			//if (log.isWarnEnabled()) log.warn("Request problem: " + ex.getMessage(), ex);
			WebUniResolver.sendResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null, "Request problem: "/* + ex.getMessage()*/);
			return;
		}

		if (log.isInfoEnabled()) log.info("Incoming resolve request for identifier: " + identifier);

		if (identifier == null) {

			WebUniResolver.sendResponse(response, HttpServletResponse.SC_BAD_REQUEST, null, "No identifier found in resolve request.");
			return;
		}

		// execute the request

		ResolveResult resolveResult;
		String resolveResultString;

		try {
			resolveResult = this.resolve(identifier);
			resolveResultString = resolveResult == null ? null : resolveResult.toJson();
		} catch (Exception ex) {

			//if (log.isWarnEnabled()) log.warn("Resolve problem for " + identifier + ": " + ex.getMessage(), ex);
			WebUniResolver.sendResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null, "Resolve problem for " + identifier + ": " /*+ ex.getMessage()*/);
			return;
		}

		if (log.isInfoEnabled()) log.info("Resolve result for " + identifier + ": " + resolveResultString);

		// no resolve result?

		if (resolveResultString == null) {

			WebUniResolver.sendResponse(response, HttpServletResponse.SC_NOT_FOUND, null, "No resolve result for " + identifier + ".");
			return;
		}

		// write resolve result

		WebUniResolver.sendResponse(response, HttpServletResponse.SC_OK, MIME_TYPE, resolveResultString);
	}
}
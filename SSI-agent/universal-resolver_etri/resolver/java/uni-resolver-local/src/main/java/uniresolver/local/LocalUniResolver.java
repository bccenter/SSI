package uniresolver.local;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import did.DID;
import did.DIDDocument;
import did.DIDURL;
import did.parser.ParserException;
import uniresolver.ResolutionException;
import uniresolver.UniResolver;
import uniresolver.driver.Driver;
import uniresolver.driver.http.HttpDriver;
import uniresolver.result.ResolveResult;

public class LocalUniResolver implements UniResolver {

	private static Logger log = LoggerFactory.getLogger(LocalUniResolver.class);

	private static final LocalUniResolver DEFAULT_RESOLVER;

	private static final Gson gson = new Gson();

	public Map<String, Driver> drivers = new HashMap<String, Driver> ();

	static {

		DEFAULT_RESOLVER = new LocalUniResolver();
	}

	public LocalUniResolver() {

	}

	public static LocalUniResolver getDefault() {

		return DEFAULT_RESOLVER;
	}

	public static LocalUniResolver fromConfigFile(String filePath) throws FileNotFoundException, IOException {
		log.info("LocalUniResolver::fromConfigFile() path=" + filePath);
		Map<String, Driver> drivers = new HashMap<String, Driver> ();

		try (Reader reader = new FileReader(new File(filePath))) {

			JsonObject jsonObjectRoot  = gson.fromJson(reader, JsonObject.class);
			JsonArray jsonArrayDrivers = jsonObjectRoot.getAsJsonArray("drivers");

			int i = 0;

			for (Iterator<JsonElement> jsonElementsDrivers = jsonArrayDrivers.iterator(); jsonElementsDrivers.hasNext(); ) {

				i++;

				JsonObject jsonObjectDriver = (JsonObject) jsonElementsDrivers.next();

				String name = jsonObjectDriver.has("name") ? jsonObjectDriver.get("name").getAsString() : null;
				String pattern = jsonObjectDriver.has("pattern") ? jsonObjectDriver.get("pattern").getAsString() : null;
				String image = jsonObjectDriver.has("image") ? jsonObjectDriver.get("image").getAsString() : null;
				String imagePort = jsonObjectDriver.has("imagePort") ? jsonObjectDriver.get("imagePort").getAsString() : null;
				String imageProperties = jsonObjectDriver.has("imageProperties") ? jsonObjectDriver.get("imageProperties").getAsString() : null;
				String url = jsonObjectDriver.has("url") ? jsonObjectDriver.get("url").getAsString() : null;

				if (name == null) name = "driver-" + i + (image != null ? ("-" + image) : "");
				if (pattern == null) throw new IllegalArgumentException("Missing 'pattern' entry in driver configuration.");
				if (image == null && url == null) throw new IllegalArgumentException("Missing 'image' and 'url' entry in driver configuration (need either one).");

				HttpDriver driver = new HttpDriver();
				driver.setPattern(pattern);

				if (url != null) {

					driver.setResolveUri(url);
				} else {

					String httpDriverUri = image.substring(image.indexOf("/") + 1);
					if (httpDriverUri.contains(":")) httpDriverUri = httpDriverUri.substring(0, httpDriverUri.indexOf(":"));
					httpDriverUri = "http://" + httpDriverUri + ":" + (imagePort != null ? imagePort : "8080" ) + "/";

					driver.setResolveUri(httpDriverUri + "1.0/identifiers/$1");

					if ("true".equals(imageProperties)) {

						driver.setPropertiesUri(httpDriverUri + "1.0/properties");
					}
				}

				drivers.put(name, driver);

				if (log.isInfoEnabled()) log.info("Added driver '" + pattern + "' at " + driver.getResolveUri() + " (" + driver.getPropertiesUri() + ")");
			}
		}

		LocalUniResolver localUniResolver = new LocalUniResolver();
		localUniResolver.setDrivers(drivers);

		return localUniResolver;
	}

	@Override
	public ResolveResult resolve(String identifier) throws ResolutionException {

		return this.resolve(identifier, null);
	}

	@Override
	public ResolveResult resolve(String identifier, Map<String, String> options) throws ResolutionException {
log.info("WSLEE LocalUniResolver::resolve(1) id=" + identifier);
		if (identifier == null) throw new NullPointerException();

		if (this.getDrivers() == null) throw new ResolutionException("No drivers configured.");

		// start time

		long start = System.currentTimeMillis();

		// parse DID URL

		DIDURL didUrl = null;
		DID did = null;

		try {

			didUrl = DIDURL.fromString(identifier);
			log.debug("Identifier " + identifier + " is a valid DID URL: " + didUrl.getDid());

			did = didUrl.getDid();
		} catch (IllegalArgumentException | ParserException ex) {

			log.debug("Identifier " + identifier + " is not a valid DID URL: " + ex.getMessage());
		}

		// resolve earlier version?

		String resolveIdentifier = did != null ? did.getDidString() : identifier;
		ResolveResult resolveResult = null;

		if (didUrl != null) {

			if (didUrl.getParametersMap().containsKey("version-time")) {

				String versionTime = didUrl.getParametersMap().get("version-time");

				DIDDocument didDocument;

				try {

					didDocument = VersionRepoService.getDidDocumentByVersionTime(resolveIdentifier, versionTime);
					resolveResult = ResolveResult.build(didDocument);
				} catch (IOException ex) {

					throw new ResolutionException("Cannot retrieve version of DID Document: " /*+ ex.getMessage()*/, ex);
				}

				if (log.isDebugEnabled()) log.debug("Retrieved version " + versionTime + " of DID Document for " + resolveIdentifier);
			}
		}

		// try all drivers

		String usedDriverId = null;
		Driver usedDriver = null;

		if (resolveResult == null) {

			for (Entry<String, Driver> driver : this.getDrivers().entrySet()) {

				if (log.isDebugEnabled()) log.debug("Attemping to resolve " + resolveIdentifier + " with driver " + driver.getValue().getClass());
				resolveResult = driver.getValue().resolve(resolveIdentifier);

				if (resolveResult != null) {

					usedDriverId = driver.getKey();
					usedDriver = driver.getValue();
					break;
				}
			}

			if (log.isDebugEnabled()) log.debug("Resolved " + resolveIdentifier + " with driver " + usedDriverId);
		}

		// result contains a new identifier to resolve (redirect)?

		List<String> redirectedIdentifiers = new ArrayList<String> ();
		List<ResolveResult> redirectedResolveResults = new ArrayList<ResolveResult> ();

		while (resolveResult != null && resolveResult.getMethodMetadata().containsKey("redirect")) {

			redirectedIdentifiers.add(resolveIdentifier);
			redirectedResolveResults.add(resolveResult);

			resolveIdentifier = (String) resolveResult.getMethodMetadata().get("redirect");

			for (Entry<String, Driver> driver : this.getDrivers().entrySet()) {

				if (log.isDebugEnabled()) log.debug("Redirect: Attemping to resolve " + resolveIdentifier + " with driver " + driver.getValue().getClass());
				resolveResult = driver.getValue().resolve(resolveIdentifier);

				if (resolveResult != null) {

					usedDriverId = driver.getKey();
					usedDriver = driver.getValue();
					break;
				}
			}

			if (log.isDebugEnabled()) log.debug("Redirect: Resolved " + resolveIdentifier + " with driver " + usedDriverId);
		}

		if (redirectedIdentifiers.isEmpty()) redirectedIdentifiers = null;
		if (redirectedResolveResults.isEmpty()) redirectedResolveResults = null;

		if (resolveResult == null && redirectedResolveResults != null && redirectedIdentifiers != null) {

			resolveIdentifier = redirectedIdentifiers.get(0);
			resolveResult = redirectedResolveResults.get(0);
			if (log.isDebugEnabled()) log.debug("Falling back to redirected identifier and resolve result: " + identifier);
		}

		// stop time

		long stop = System.currentTimeMillis();

		// no driver was able to fulfill a request?

		if (resolveResult == null) {

			if (log.isDebugEnabled()) log.debug("No resolve result.");
			return null;
		}

		// dereferencing

		Integer[] selectedServices = null;

		if (didUrl != null) {

			String selectServiceName = didUrl.getParametersMap() == null ? null : didUrl.getParametersMap().get("service");
			String selectServiceType = didUrl.getParametersMap() == null ? null : didUrl.getParametersMap().get("service-type");

			if (selectServiceName != null || selectServiceType != null) {

				selectedServices = resolveResult.getDidDocument().selectServices(selectServiceName, selectServiceType).keySet().toArray(new Integer[0]);
			}
		}

		Integer[] selectedKeys = null;

		if (didUrl != null) {

			String selectKeyName = didUrl.getParametersMap() == null ? null : didUrl.getParametersMap().get("key");
			String selectKeyType = didUrl.getParametersMap() == null ? null : didUrl.getParametersMap().get("key-type");

			if (selectKeyName != null || selectKeyType != null) {

				selectedKeys = resolveResult.getDidDocument().selectKeys(selectKeyName, selectKeyType).keySet().toArray(new Integer[0]);
			}
		}

		// add RESOLVER METADATA

		Map<String, Object> resolverMetadata = new LinkedHashMap<String, Object> ();
		resolverMetadata.put("duration", Long.valueOf(stop - start));
		if (usedDriverId != null) resolverMetadata.put("driverId", usedDriverId);
		if (usedDriver != null) resolverMetadata.put("driver", usedDriver.getClass().getSimpleName());
		if (didUrl != null) resolverMetadata.put("didUrl", didUrl.toJsonObject());
		if (redirectedIdentifiers != null) resolverMetadata.put("redirectedIdentifiers", redirectedIdentifiers);
		if (selectedServices != null) resolverMetadata.put("selectedServices", selectedServices);
		if (selectedKeys != null) resolverMetadata.put("selectedKeys", selectedKeys);

		resolveResult.setResolverMetadata(resolverMetadata);

		// done

		return resolveResult;
	}

	@Override
	public Map<String, Map<String, Object>> properties() throws ResolutionException {

		if (this.getDrivers() == null) throw new ResolutionException("No drivers configured.");

		Map<String, Map<String, Object>> properties = new HashMap<String, Map<String, Object>> ();

		for (Entry<String, Driver> driver : this.getDrivers().entrySet()) {

			if (log.isDebugEnabled()) log.debug("Loading properties for driver " + driver.getKey() + " (" + driver.getValue().getClass().getSimpleName() + ")");

			Map<String, Object> driverProperties = driver.getValue().properties();
			if (driverProperties == null) driverProperties = Collections.emptyMap();

			properties.put(driver.getKey(), driverProperties);
		}

		if (log.isDebugEnabled()) log.debug("Loading properties: " + properties);

		return properties;
	}

	/*
	 * Getters and setters
	 */
	private Map<String, Driver> getDrivers() {
		return this.drivers;
	}

	@SuppressWarnings("unchecked")
	public <T extends Driver> T getDriver(Class<T> driverClass) {

		for (Driver driver : this.getDrivers().values()) {

			if (driverClass.isAssignableFrom(driver.getClass())) return (T) driver;
		}

		return null;
	}

	private void setDrivers(Map<String, Driver> drivers) {

		this.drivers = drivers;
	}
}

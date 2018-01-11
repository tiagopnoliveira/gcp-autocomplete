/**
 * Copyright 2015 Google Inc. All Rights Reserved.
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

package com.example.appengine.autocomplete;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.logging.Level;

import java.util.*;
import com.google.appengine.api.memcache.*;
import com.google.appengine.api.datastore.*;
import com.google.appengine.api.datastore.Query.*;
import org.apache.commons.lang3.StringEscapeUtils;

@SuppressWarnings("serial")
public class ProductNameAutocompleteJSONServlet extends HttpServlet {
	private final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
	private final int minimumPrefixSize = 1;
	private final int maximumPrefixSize = 10;
	private final int maxResults = 12;
	private final String datastoreKindName = "AutoCompletePrefixes";
	private final String datastoreProductEntryName = "entry";
	private final String datastorePrefixesEntryName = "prefixes";
	private final String jsonCallbackFunctionName = "jsonCallback";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getRequestURI();
    if (path.startsWith("/favicon.ico")) {
      return; // ignore the request for favicon.ico
    }

   	String[] qContents = request.getParameterValues("q");
   	if(qContents == null || qContents.length == 0) {
		PrintWriter out = response.getWriter();
		out.println("Invalid usage");
		return;
	}
	
	String[] queryElements = qContents[0].trim().split("\\s");
	ArrayList<String> inputPrefixes = new ArrayList<String>();
	for(String s : queryElements) {
		if(s.length() >= minimumPrefixSize) {
			if(s.length() > maximumPrefixSize) {
				s = s.substring(0,maximumPrefixSize);
			}
			inputPrefixes.add(s.toLowerCase());
		}
	}
	Collections.sort(inputPrefixes);
	StringBuilder keyBuilder = new StringBuilder();
	for(String prefix : inputPrefixes) {
		keyBuilder.append(prefix + " ");
	}
	String jsonP = null;

	// try to read from cache
    MemcacheService memCache = MemcacheServiceFactory.getMemcacheService();
    memCache.setErrorHandler(ErrorHandlers.getConsistentLogAndContinue(Level.INFO));
    byte[] key = keyBuilder.toString().trim().getBytes();
    byte[] cacheValue = (byte[]) memCache.get(key);
    
	if(cacheValue == null) {
		ArrayList<String> suggestions = generateListAutoCompleteSuggestions(inputPrefixes);
		jsonP = outputJSONPFromSuggestions(suggestions);
		memCache.put(key, jsonP.getBytes());
	} else {
		jsonP = new String(cacheValue);
	}
	
	response.setContentType("application/javascript;charset=UTF-8");           
    response.setHeader("Cache-Control", "no-cache");

	PrintWriter out = response.getWriter();
	out.print(jsonP);

    //doDebugging(suggestions, response);
  }
  
  private String outputJSONPFromSuggestions(ArrayList<String> suggestions) throws IOException {
      StringBuilder result = new StringBuilder(jsonCallbackFunctionName + "([");
      boolean firstElement = true;
      for(String suggestion : suggestions) {
		  if(!firstElement) {
			  result.append(",");
		  } else {
			  firstElement = false;
		  }
		  result.append("\"" + StringEscapeUtils.escapeJson(suggestion) + "\"");
	  }
	  result.append("])");
      
	  return result.toString();
  }
  
  private ArrayList<String> generateListAutoCompleteSuggestions(ArrayList<String> inputPrefixes) {
    Filter filter = null;
    if(inputPrefixes == null || inputPrefixes.size() == 0) {
		return new ArrayList<String>();
	}
    
    if(inputPrefixes.size() > 1) {
		ArrayList<Filter> subFilters = new ArrayList<Filter>();
		for(String prefix : inputPrefixes) {
			subFilters.add(new FilterPredicate(datastorePrefixesEntryName, FilterOperator.EQUAL, prefix));
		}
		filter = new CompositeFilter(CompositeFilterOperator.AND, subFilters);
	} else {
		filter = new FilterPredicate(datastorePrefixesEntryName, FilterOperator.EQUAL, inputPrefixes.get(0));
	}
	
	Query query = new Query(datastoreKindName).setFilter(filter);
//	query.addProjection(new PropertyProjection(datastoreProductEntryName, String.class));

//	List<Entity> entities = datastore.prepare(query).asList(FetchOptions.Builder.withDefaults());
	List<Entity> entities = datastore.prepare(query).asList(FetchOptions.Builder.withLimit(maxResults));


	ArrayList<String> results = new ArrayList<String>();
	for(Entity entity : entities) {
		String entry = (String) entity.getProperty(datastoreProductEntryName);
		results.add(entry);
	}
	
	return results;
  }
  
  private void doDebugging(List<String> suggestions, HttpServletResponse response) throws IOException {
    PrintWriter out = response.getWriter();

	out.println("<br/>");
	out.println("<b>Results:</b> " + suggestions.size());
	out.println("<br/>");
	out.println("<br/>");
	for(String s : suggestions) {
		out.println(s);
		out.println("<br/>");
	}
  }
}

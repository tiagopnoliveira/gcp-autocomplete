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

package com.example.appengine.helloworld;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.*;
import com.google.appengine.api.datastore.*;
import com.google.appengine.api.datastore.Query.*;

// [START example]
@SuppressWarnings("serial")
public class HelloServlet extends HttpServlet {
	private final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
	private final int minimumPrefixSize = 3;
	private final int maxResults = 6;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    PrintWriter out = response.getWriter();
   	String[] qContents = request.getParameterValues("q");
   	if(qContents.length == 0) {
		//TODO: Error stuff
	}
	
	String[] queryElements = qContents[0].split("\\s");
	ArrayList<String> inputPrefixes = new ArrayList<String>();
	for(String s : queryElements) {
		if(s.length() >= minimumPrefixSize) {
			inputPrefixes.add(s);
		}
	}

	ArrayList<Filter> subFilters = new ArrayList<Filter>();
	subFilters.add(new FilterPredicate("prefix", FilterOperator.EQUAL, "AAA"));
	subFilters.add(new FilterPredicate("entries", FilterOperator.EQUAL, "Duracell - AAA Batteries (4-Pack)"));
	Filter filter = new CompositeFilter(CompositeFilterOperator.AND, subFilters);

	Query query = new Query("AutocompletePrefixes").setFilter(filter);
	out.println("<br/>");
	out.println("<b>query used:</b> " + query.toString());
	out.println("<br/>");

	List<Entity> entities = datastore.prepare(query).asList(FetchOptions.Builder.withDefaults());
	for(Entity entity : entities) {
		ArrayList<String> entries = (ArrayList<String>) entity.getProperty("entries");
		out.println("<br/>");
		out.println("<b>processing subentry now:</b> ");
		out.println("<br/>");
		for(String s : entries) {
			out.println(s);
			out.println("<br/>");
		}
	}
	/*
    Filter filter = null;
    if(inputPrefixes.size() > 1) {
		ArrayList<Filter> subFilters = new ArrayList<Filter>();
		for(String prefix : inputPrefixes) {
			subFilters.add(new FilterPredicate("prefix", FilterOperator.EQUAL, prefix));
		}
		filter = new CompositeFilter(CompositeFilterOperator.OR, subFilters);
	} else {
		filter = new FilterPredicate("prefix", FilterOperator.EQUAL, inputPrefixes.get(0));
	}
	
	Query query = new Query("AutocompletePrefixes").setFilter(filter);
		out.println("<br/>");
		out.println("<b>query used:</b> " + query.toString());
		out.println("<br/>");
	List<Entity> entities = datastore.prepare(query).asList(FetchOptions.Builder.withDefaults());
	ArrayList<String> results = null;
	for(Entity entity : entities) {
		ArrayList<String> entries = (ArrayList<String>) entity.getProperty("entries");

		out.println("<br/>");
		out.println("<b>processing subentry now:</b> ");
		out.println("<br/>");
	for(String s : entries) {
		out.println(s);
		out.println("<br/>");
	}


		if(results == null) {
			results = entries;
		} else {
			results.retainAll(entries);
		}
	}
	
	if(results.size() > maxResults) {
		results.subList(maxResults, results.size()).clear();
	}
	
	out.println("<br/>");
	out.println("<b>Results:</b> " + results.size());
	out.println("<br/>");
	out.println("<br/>");
	for(String s : results) {
		out.println(s);
		out.println("<br/>");
	}
	*/
  }
}
// [END example]

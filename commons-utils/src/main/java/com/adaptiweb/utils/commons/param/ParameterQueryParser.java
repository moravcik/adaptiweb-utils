package com.adaptiweb.utils.commons.param;


public class ParameterQueryParser {

	public interface UrlDecoderProvider {
		String decode(String value);
	}

	public static final UrlDecoderProvider plainUrlDecoder = new UrlDecoderProvider() {
		@Override
		public String decode(String value) {
			return value;
		}
	};
	
	public static ParameterMap parseParameters(String queryString, UrlDecoderProvider decoder) {
		ParameterMap parameterMap = new ParameterMap();
		String parameterQuery = queryString;
		if (queryString.startsWith("?") || queryString.startsWith("#")) parameterQuery = queryString.substring(1);
		else if (queryString.startsWith("/#")) parameterQuery = queryString.substring(2);
		for (String param : parameterQuery.split("&")) {
			String[] nameAndValue = param.split("=");
			if (nameAndValue.length == 2) 
				parameterMap.put(nameAndValue[0], decoder.decode(nameAndValue[1]));
		}
		return parameterMap;
	}

}

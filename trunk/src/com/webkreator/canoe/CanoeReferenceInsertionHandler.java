package com.webkreator.canoe;

import org.apache.velocity.app.event.ReferenceInsertionEventHandler;


public class CanoeReferenceInsertionHandler implements
		ReferenceInsertionEventHandler {

	public static final String SAFE_REFERENCE_PREFIX = "$_x.";

	protected Canoe qlueWriter;

	public CanoeReferenceInsertionHandler(Canoe qlueWriter) {
		this.qlueWriter = qlueWriter;
	}

	@Override
	public Object referenceInsert(String arg0, Object arg1) {
		if (arg0.startsWith(SAFE_REFERENCE_PREFIX)) {
			return arg1;
		}
		
		if (arg1 == null) {
			return null;
		}

		switch (qlueWriter.currentContext()) {
		case Canoe.CTX_HTML:
			return EncodingTool.encodeForHTML(arg1.toString());
		case Canoe.CTX_JS:
			return EncodingTool.encodeForJavaScript(arg1.toString());
		case Canoe.CTX_URI:
			return EncodingTool.encodeForURL(arg1.toString());
		case Canoe.CTX_CSS:
			return EncodingTool.encodeForCSS(arg1.toString());
		case Canoe.CTX_SUPPRESS:
		default:
			// Do nothing
			break;
		}

		// Suppressed output
		return new String("");
	}
}

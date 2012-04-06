package com.webkreator.qlue;

import com.webkreator.qlue.view.JsonView;
import com.webkreator.qlue.view.View;

public class JsonPage extends Page {
	
	@Override
	public View onValidationError() {
		context.response.setStatus(400);
		return new JsonView(getErrors());
	}
}

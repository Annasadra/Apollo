package com.apollocurrency.aplwallet.apl.core.rest.exception;

import com.apollocurrency.aplwallet.apl.core.rest.ApiErrors;
import com.apollocurrency.aplwallet.apl.core.rest.utils.ResponseBuilder;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;


/**
 * Global handler for {@link ClientErrorException} thrown from REST endpoints
 *
 * @author isegodin
 */
@Provider
public class ClientErrorExceptionMapper implements ExceptionMapper<ClientErrorException> {

    @Override
    public Response toResponse(ClientErrorException exception) {
        ResponseBuilder responseBuilder = ResponseBuilder.apiError(
                ApiErrors.INTERNAL_SERVER_EXCEPTION,
                exception.getMessage());
        return responseBuilder.build();
    }

}
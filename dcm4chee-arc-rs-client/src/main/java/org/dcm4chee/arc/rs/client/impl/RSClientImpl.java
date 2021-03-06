/*
 * ** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2016
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.rs.client.impl;

import org.dcm4chee.arc.entity.QueueMessage;
import org.dcm4chee.arc.keycloak.AccessTokenRequestor;
import org.dcm4chee.arc.qmgt.Outcome;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.dcm4chee.arc.rs.client.RSClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Nov 2016
 */
@ApplicationScoped
public class RSClientImpl implements RSClient {

    @Inject
    private QueueManager queueManager;

    @Inject
    private AccessTokenRequestor accessTokenRequestor;

    @Override
    public void scheduleRequest(
            String method, String uri, byte[] content, String keycloakServerID, boolean tlsAllowAnyHostName, boolean tlsDisableTrustManager)
            throws QueueSizeLimitExceededException {
        try {
            ObjectMessage msg = queueManager.createObjectMessage(content);
            msg.setStringProperty("Method", method);
            msg.setStringProperty("URI", uri);
            msg.setStringProperty("KeycloakServerID", keycloakServerID);
            msg.setStringProperty("TLSAllowAnyHostname", String.valueOf(tlsAllowAnyHostName));
            msg.setStringProperty("TLSDisableTrustManager", String.valueOf(tlsDisableTrustManager));
            queueManager.scheduleMessage(QUEUE_NAME, msg, Message.DEFAULT_PRIORITY, null);
        } catch (JMSException e) {
            throw new JMSRuntimeException(e.getMessage(), e.getErrorCode(), e.getCause());
        }
    }

    @Override
    public Outcome request(String method, String uri, String keycloakServerID, boolean allowAnyHostname,
            boolean disableTrustManager, byte[] content) throws Exception {
        ResteasyClient client = accessTokenRequestor.resteasyClientBuilder(uri, allowAnyHostname, disableTrustManager)
                .build();
        WebTarget target = client.target(uri);
        Response response = null;
        Outcome outcome;
        Invocation.Builder request = target.request();
        if (keycloakServerID != null) {
             request.header("Authorization", "Bearer " + accessTokenRequestor.getAccessTokenString(keycloakServerID));
        }
        switch (method) {
            case "DELETE":
                response = request.delete();
                break;
            case "POST":
                response = request.post(Entity.json(content));
                break;
            case "PUT":
                response = request.put(Entity.json(content));
                break;
        }
        outcome = buildOutcome(Response.Status.fromStatusCode(response.getStatus()), response.getStatusInfo());
        response.close();
        return outcome;
    }

    private Outcome buildOutcome(Response.Status status, Response.StatusType st) {
        switch (status) {
            case NO_CONTENT:
                return new Outcome(QueueMessage.Status.COMPLETED, "Completed : " + st);
            case REQUEST_TIMEOUT:
            case SERVICE_UNAVAILABLE:
                return new Outcome(QueueMessage.Status.SCHEDULED, "Retry : " + st);
            case NOT_FOUND:
            case FORBIDDEN:
            case BAD_REQUEST:
            case UNAUTHORIZED:
            case INTERNAL_SERVER_ERROR:
                return new Outcome(QueueMessage.Status.FAILED, st.toString());
        }
        return new Outcome(QueueMessage.Status.WARNING, "Http Response Status from other archive is : " + status.toString());
    }
}

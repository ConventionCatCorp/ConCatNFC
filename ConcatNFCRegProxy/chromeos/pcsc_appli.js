/** @license
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @fileoverview Entry point of the Smart Card Client App background script (see
 * <https://developer.chrome.com/apps/event_pages>).
 */

/**
 * Client title for the connection to the server App.
 *
 * Currently this is only used for the debug logs produced by the server App.
 * @const
 */
var CLIENT_TITLE = 'example_js_client_app';

/**
 * Context for talking to the Smart Card Connector app for making PC/SC API
 * requests.
 * @type {GoogleSmartCard.PcscLiteClient.Context}
 */
var apiContext = null;

/**
 * Object that allows to make PC/SC API requests to the Smart Card Connector
 * app.
 * @type {GoogleSmartCard.PcscLiteClient.API}
 */
var api = null;

var API = GoogleSmartCard.PcscLiteClient.API;


/**
 * PC/SC-Lite SCard context.
 * @type {int}
 */
var sCardContext = null;

function initialize() {
  myLog('Establishing connection to the Connector app...');
  console.log('Establishing connection to the Connector app...');
  apiContext = new GoogleSmartCard.PcscLiteClient.Context(CLIENT_TITLE);
  apiContext.addOnInitializedCallback(onInitializationSucceeded);
  apiContext.addOnDisposeCallback(contextDisposedListener);
  apiContext.initialize();
}

function onInitializationSucceeded(constructedApi) {
  myLog('Successfully connected to the Connector app');
  console.log('Successfully connected to the Connector app');
  api = constructedApi;
  establishContext();
}

function establishContext() {
  myLog('Establishing PC/SC-Lite context...');
  console.log('Establishing PC/SC-Lite context...');
  api.SCardEstablishContext(
      GoogleSmartCard.PcscLiteClient.API.SCARD_SCOPE_SYSTEM, null, null).then(
      function(result) {
        result.get(onContextEstablished, onPcscLiteError);
      }, onRequestFailed);
}

/** @param {int} establishedSCardContext PC/SC-Lite SCard context. */
function onContextEstablished(establishedSCardContext) {
  myLog('Established PC/SC-Lite context ' + establishedSCardContext);
  console.log('Established PC/SC-Lite context ' + establishedSCardContext);
  sCardContext = establishedSCardContext;
  listReaders();
}

function listReaders() {
  myLog('Obtaining list of PC/SC-lite readers...');
  console.log('Obtaining list of PC/SC-lite readers...');
  api.SCardListReaders(sCardContext, null).then(function(result) {
    result.get(onReadersListed, onPcscLiteError);
  }, onRequestFailed);
}

/** @param {!Array.<string>} readers List of reader names. */
function onReadersListed(readers) {
  myLog('List of PC/SC-Lite readers: ' + readers);
  console.log('List of PC/SC-Lite readers: ' + readers);

    // Use the 1st reader
    reader = readers[0];
    myLog('Using reader: ' + reader);

    myCode(reader);
}

function contextDisposedListener() {
  myLog('Connection to the server app was shut down');
  console.warn('Connection to the server app was shut down');
  sCardContext = null;
  api = null;
  apiContext = null;
}

/** @param {int} errorCode PC/SC-Lite error code. */
function onPcscLiteError(errorCode) {
  myLog('PC/SC-Lite request failed with error code ' + errorCode);
  console.warn('PC/SC-Lite request failed with error code ' + errorCode);
}

/** @param {*} error The exception that happened during the request. */
function onRequestFailed(error) {
  myLog('Failed to perform request to the Smart Card Connector app: ' +
               error);
  console.warn('Failed to perform request to the Smart Card Connector app: ' +
               error);
}


function myLog(text)
{
    $("#logs").append($("<li>").text(text));
}

function myCode(readerName)
{
    myLog('Connect to the reader: ' + readerName);
    api.SCardConnect(sCardContext,
        readerName,
        API.SCARD_SHARE_SHARED,
        API.SCARD_PROTOCOL_ANY).then(function(result) {
            result.get(onConnected, onPcscLiteError);
    }, onRequestFailed);
}

function onConnected(cardHandle, protocol)
{
    APDU_SELECT = [0xff, 0x00, 0x00, 0x00, 0x3, 0xd4, 0x42, 0x60];

    myLog('Sending ' + dump(APDU_SELECT));
    api.SCardTransmit(
      cardHandle,
      protocol == API.SCARD_PROTOCOL_T0 ?
          API.SCARD_PCI_T0 : API.SCARD_PCI_T1,
      APDU_SELECT).then(function(result) {
        result.get(function (ioRecvPci, response) {
            myLog('response: ' + dump(response));

            // get the 2 last bytes
            sw = response.slice(-2);
            if (sw[0] == 0x90 && sw[1] == 0x00)
            {
                APDU_COMMAND = [0xFF, 0xB0, 0x00, 0x0a, 0x04];
                myLog('Sending ' + dump(APDU_COMMAND));
                api.SCardTransmit(
                    cardHandle,
                    protocol == API.SCARD_PROTOCOL_T0 ?
                    API.SCARD_PCI_T0 : API.SCARD_PCI_T1,
                    APDU_COMMAND).then(function(result) {
                        result.get(function(ioRecvPci, response) {
                            myLog('response: ' + dump(response));

                            // get the 2 last bytes
                            sw = response.slice(-2);
                            if (sw[0] == 0x90 && sw[1] == 0x00)
                            {
                                // convert to an ASCII string
                                result = "";
                                for (var i = 0; i < response.length; i++)
                                {
                                    result += String.fromCharCode(response[i]);
                                }
                                myLog('response: ' + result);
                            }
                        }, onPcscLiteError);
                    }, onRequestFailed);
            }
            }, onPcscLiteError);
      }, onRequestFailed);
}


function dump(bytes)
{
    return (bytes.map(function (x) {
        return ('00' + x.toString(16).toUpperCase()).substr(-2)
    })).join(" ");
}

window.onload = initialize;


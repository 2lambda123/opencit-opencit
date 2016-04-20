'''
Created on Mar 5, 2012

@author: jabuhacx
'''

import httplib
import logging
import socket
import urllib
import ssl
import MtWilsonHttpAuthorization

'''
from nova import log as logging
from nova import exception
'''
LOG = logging.getLogger('django_openstack.attestation.client')
class exception:
    def NotAuthorized(self):
        return ""

class HTTPSClientAuthConnection(httplib.HTTPSConnection):
    """ Class to make a HTTPS connection, with support for full client-based SSL Authentication"""

    def __init__(self, host, port, key_file, cert_file, ca_file, timeout=None):
        self.mtwilson = MtWilsonHttpAuthorization.MtWilsonHttpAuthorization("root","root")
        httplib.HTTPSConnection.__init__(self, host, port,
                                        key_file=key_file, cert_file=cert_file)
        self.host = host
        self.post = port
        self.key_file = key_file
        self.cert_file = cert_file
        self.ca_file = ca_file
        self.timeout = timeout

    def connect(self):
        """ Connect to a host on a given (SSL) port.
            Use ca_file pointing somewhere, use it to check Server Certificate.
            Redefined/copied and extended from httplib.py:1105 (Python 2.6.x).
        """
        host = self.host
        port = self.port
        sock = socket.create_connection((self.host, self.port), self.timeout)

        LOG.debug(_("Connecting to Https server host %(host)s port %(port)s " % locals()))

        if self._tunnel_host:
            self.sock = sock
            self._tunnel()
        # If there's no CA File, don't force Server Certificate Check
        if self.ca_file:
            self.sock = ssl.wrap_socket(sock, self.key_file, self.cert_file,
                        ssl_version=ssl.PROTOCOL_SSLv23,
                        ca_certs=self.ca_file,
                        cert_reqs=ssl.CERT_REQUIRED)
            LOG.debug(_("Https server certified " % locals()))
            s = self.sock
            cert = s.getpeercert()
        else:
            self.sock = ssl.wrap_socket(sock, self.key_file, self.cert_file,
                                                cert_reqs=ssl.CERT_NONE)


class BaseClient(object):

    """A base client class"""

    CHUNKSIZE = 65536

    def __init__(self, host, port, use_ssl, auth_user, auth_passwd,
                                 key_file=None, cert_file=None, ca_file=None):
        """
        Creates a new client to some service.

        :param host: The host where service resides
        :param port: The port where service resides
        :param use_ssl: Should we use HTTPS?
        :param auth_user: user name to connect with attestation server
        :param auth_passwd: password to get attestation server authentication
        """
        self.host = host
        self.port = port
        self.use_ssl = use_ssl
        self.auth_user = auth_user
        self.auth_passwd = auth_passwd
        self.connection = None
        self.key_file = key_file
        self.cert_file = cert_file
        self.ca_file = ca_file

    def set_auth_blob(self, auth_user, auth_passwd):
        """
        Updates the authentication token for this client connection.
        """
        self.auth_user = auth_user
        self.auth_passwd = auth_passwd

    def get_connection(self):
        """
        Returns the proper connection type
        """
        host = self.host
        port = self.port
        if self.use_ssl:
                        return HTTPSClientAuthConnection(self.host, self.port,
                                                                                         key_file=self.key_file,
                                                                                         cert_file=self.cert_file,
                                                                                         ca_file=self.ca_file)
        else:
            return httplib.HTTPConnection(self.host, self.port)

    def do_request(self, method, action, body=None, headers=None, params=None):
        """
        Connects to the server and issues a request.  Handles converting
        any returned HTTP error status codes to OpenStack/Glance exceptions
        and closing the server connection. Returns the result data, or
        raises an appropriate exception.

        """

        LOG.debug(_("method is %(method)s action is %(action)s body or content is  %(body)s header is %(headers)s" % locals()))

        if type(params) is dict:

            # remove any params that are None
            for (key, value) in params.items():
                if value is None:
                    del params[key]

            action += '?' + urllib.urlencode(params)

        try:
            c = self.get_connection()
            headers = {"Content-type": "application/json","Accept":"application/json"}
            headers['Authorization'] = self.mtwilson.getAuthorization(method, action, body)
            #headers = headers or {}
            #if 'x-auth-user' not in headers and self.auth_user:
            #    headers['x-auth-user'] = self.auth_user
            #if 'x-auth-passwd' not in headers and self.auth_passwd:
            #    headers['x-auth-passwd'] = self.auth_passwd

            # Do a simple request or a chunked request, depending
            # on whether the body param is a file-like object and
            # the method is PUT or POST
            #if hasattr(body, 'read') and method.lower() in ('post', 'put'):
            if body:
                # query is in body
                c.putrequest(method, action)

                for header, value in headers.items():
                    c.putheader(header, value)
                #c.putheader('Content-type', 'text/json')
                c.putheader('Content-length', len(body))
                #c.putheader('Transfer-Encoding', 'chunked')
                c.endheaders()

                #chunk = body.read(self.CHUNKSIZE)
                c.send('%s' % (body))
                c.send('0\r\n\r\n')
            else:
                # Simple request.through uri
                c.request(method, action, body, headers)
            res = c.getresponse()
            #status_code = self.get_status_code(res)
            status_code = res.status
            if status_code in (httplib.OK,
                               httplib.CREATED,
                               httplib.ACCEPTED,
                               httplib.NO_CONTENT):
                return res
            elif status_code == httplib.UNAUTHORIZED:
                raise exception.NotAuthorized
            elif status_code == httplib.FORBIDDEN:
                raise exception.NotAuthorized
            elif status_code == httplib.NOT_FOUND:
                raise exception.NotFound
            elif status_code == httplib.CONFLICT:
                raise exception.Duplicate(res.read())
            elif status_code == httplib.BAD_REQUEST:
                raise exception.Invalid(res.read())
            elif status_code == httplib.INTERNAL_SERVER_ERROR:
                raise Exception("Internal Server error: %s" % res.read())
            else:
                raise Exception("Unknown error occurred! %s" % res.read())

        except (socket.error, IOError), e:
            raise Exception("Unable to connect to server. Got error: %s" % e)

    def get_status_code(self, response):
        """
        Returns the integer status code from the response, which
        can be either a Webob.Response (used in testing) or httplib.Response
        """
        if hasattr(response, 'status_int'):
            return response.status_int
        else:
            return response.status

    def _extract_params(self, actual_params, allowed_params):
        """
        Extract a subset of keys from a dictionary. The filters key
        will also be extracted, and each of its values will be returned
        as an individual param.

        :param actual_params: dict of keys to filter
        :param allowed_params: list of keys that 'actual_params' will be
                               reduced to
        :retval subset of 'params' dict
        """
        try:
            # expect 'filters' param to be a dict here
            result = dict(actual_params.get('filters'))
        except TypeError:
            result = {}

        for allowed_param in allowed_params:
            if allowed_param in actual_params:
                result[allowed_param] = actual_params[allowed_param]

        return result

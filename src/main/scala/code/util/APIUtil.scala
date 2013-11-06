package code.util

import net.liftweb.http.JsonResponse
import net.liftweb.json.Extraction
import net.liftweb.json.JsonAST.JValue
import net.liftweb.http.js.JsExp
import net.liftweb.common.Full
import net.liftweb.util.Helpers._
import net.liftweb.http.S
import net.liftweb.http.js.JE.JsRaw
import scala.collection.JavaConversions.asScalaSet

object APIUtil {

  implicit val formats = net.liftweb.json.DefaultFormats
  implicit def errorToJson(error: ErrorMessage): JValue = Extraction.decompose(error)
  val headers = ("Access-Control-Allow-Origin","*") :: Nil

  case class ErrorMessage(
    error: String
  )

  case class SuccessMessage(
    success: String
  )

  case class BankAccountJSON(
    account_number: String,
    blz_iban: String,
    pin_code: String
  )

  case class PinCodeJSON(
    pin_code: String
  )


  def httpMethod : String =
    S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }

  def isThereAnOAuthHeader : Boolean = {
    S.request match {
      case Full(a) =>  a.header("Authorization") match {
        case Full(parameters) => parameters.contains("OAuth")
        case _ => false
      }
      case _ => false
    }
  }

  def noContentJsonResponse : JsonResponse =
    JsonResponse(JsRaw(""), headers, Nil, 204)

  def successJsonResponse(json: JsExp, httpCode : Int = 200) : JsonResponse =
    JsonResponse(json, headers, Nil, httpCode)

  def errorJsonResponse(message : String = "error", httpCode : Int = 400) : JsonResponse =
    JsonResponse(Extraction.decompose(ErrorMessage(message)), headers, Nil, httpCode)

  def oauthHeaderRequiredJsonResponce : JsonResponse =
    JsonResponse(Extraction.decompose(ErrorMessage("Authentication via OAuth is required")), headers, Nil, 400)

  /** Import this object's methods to add signing operators to dispatch.Request */
  object OAuth {
    import dispatch.{Req => Request}
    import collection.Map
    import collection.immutable.{TreeMap, Map=>IMap}
    import collection.mutable.Set
    import javax.crypto
    import java.net.URI

    import org.apache.http.protocol.HTTP.UTF_8
    import net.liftweb.util.Helpers

    case class Consumer(key: String, secret: String)
    case class Token(value: String, secret: String)
    object Token {
      def apply[T <: Any](m: Map[String, T]): Option[Token] = List("oauth_token", "oauth_token_secret").flatMap(m.get) match {
        case value :: secret :: Nil => Some(Token(value.toString, secret.toString))
        case _ => None
      }
    }

    /** @return oauth parameter map including signature */
    def sign(method: String, url: String, user_params: Map[String, Any], consumer: Consumer, token: Option[Token], verifier: Option[String], callback: Option[String]) = {
      val oauth_params = IMap(
        "oauth_consumer_key" -> consumer.key,
        "oauth_signature_method" -> "HMAC-SHA1",
        "oauth_timestamp" -> (System.currentTimeMillis / 1000).toString,
        "oauth_nonce" -> System.nanoTime.toString,
        "oauth_version" -> "1.0"
      ) ++ token.map { "oauth_token" -> _.value } ++
        verifier.map { "oauth_verifier" -> _ } ++
        callback.map { "oauth_callback" -> _ }

      val encoded_ordered_params = (
        new TreeMap[String, String] ++ (user_params ++ oauth_params map %%)
      ) map { case (k, v) => k + "=" + v } mkString "&"

      val message =
        %%(method.toUpperCase :: url :: encoded_ordered_params :: Nil)

      val SHA1 = "HmacSHA1"
      val key_str = %%(consumer.secret :: (token map { _.secret } getOrElse "") :: Nil)
      val key = new crypto.spec.SecretKeySpec(bytes(key_str), SHA1)
      val sig = {
        val mac = crypto.Mac.getInstance(SHA1)
        mac.init(key)
        Helpers.base64Encode(mac.doFinal(bytes(message)))
      }
      oauth_params + ("oauth_signature" -> sig)
    }

    /** Out-of-band callback code */
    val oob = "oob"

    /** Map with oauth_callback set to the given url */
    def callback(url: String) = IMap("oauth_callback" -> url)

    //normalize to OAuth percent encoding
    private def %% (str: String): String = {
      val remaps = ("+", "%20") :: ("%7E", "~") :: ("*", "%2A") :: Nil
      (encode_%(str) /: remaps) { case (str, (a, b)) => str.replace(a,b) }
    }
    private def %% (s: Seq[String]): String = s map %% mkString "&"
    private def %% (t: (String, Any)): (String, String) = (%%(t._1), %%(t._2.toString))

    private def bytes(str: String) = str.getBytes(UTF_8)

    /** Add OAuth operators to dispatch.Request */
    implicit def Request2RequestSigner(r: Request) = new RequestSigner(r)

    /** @return %-encoded string for use in URLs */
    def encode_% (s: String) = java.net.URLEncoder.encode(s, org.apache.http.protocol.HTTP.UTF_8)

    /** @return %-decoded string e.g. from query string or form body */
    def decode_% (s: String) = java.net.URLDecoder.decode(s, org.apache.http.protocol.HTTP.UTF_8)

    class RequestSigner(rb: Request) {
      private val r = rb.build()
      @deprecated("use <@ (consumer, callback) to pass the callback in the header for a request-token request")
      def <@ (consumer: Consumer): Request = sign(consumer, None, None, None)
      /** sign a request with a callback, e.g. a request-token request */
      def <@ (consumer: Consumer, callback: String): Request = sign(consumer, None, None, Some(callback))
      /** sign a request with a consumer, token, and verifier, e.g. access-token request */
      def <@ (consumer: Consumer, token: Token, verifier: String): Request =
        sign(consumer, Some(token), Some(verifier), None)
      /** sign a request with a consumer and a token, e.g. an OAuth-signed API request */
      def <@ (consumer: Consumer, token: Token): Request = sign(consumer, Some(token), None, None)
      def <@ (consumerAndToken: Option[(Consumer,Token)]): Request = {
        consumerAndToken match {
          case Some(cAndt) => sign(cAndt._1, Some(cAndt._2), None, None)
          case _ => rb
        }
      }

      /** Sign request by reading Post (<<) and query string parameters */
      private def sign(consumer: Consumer, token: Option[Token], verifier: Option[String], callback: Option[String]) = {
        val split_decode: (String => IMap[String, String]) = {
          case null => IMap.empty
          case query =>
            if(query.isEmpty)
              IMap.empty
            else
              IMap.empty ++ query.trim.split('&').map { nvp =>
                nvp.split("=").map(decode_%) match {
                  case Array(name) => name -> ""
                  case Array(name, value) => name -> value
                }
              }
        }
        val oauth_url = r.getUrl.split('?')(0)
        val query_params = split_decode(tryo{r.getUrl.split('?')(1)}getOrElse(""))
        val params = r.getParams
        val keys : Set[String] = tryo{asScalaSet(params.keySet)}.getOrElse(Set())
        val form_params = keys.map{ k =>
          (k -> params.get(k))
        }
        val oauth_params = OAuth.sign(r.getMethod, oauth_url,
                                      query_params ++ form_params,
                                      consumer, token, verifier, callback)

        def addHeader(rb : Request, values: Map[String, String]) : Request = {
          values.map{ case (k,v) =>
            rb.setHeader(k, v)
          }
          rb
        }

        addHeader(
          rb,
          IMap("Authorization" -> ("OAuth " + oauth_params.map {
            case (k, v) => (encode_%(k)) + "=\"%s\"".format(encode_%(v))
          }.mkString(",") ))
        )
      }
    }
  }
}

package net.azib.photos

import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import java.util.*
import java.util.logging.Logger
import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponse.SC_MOVED_PERMANENTLY
import javax.servlet.http.HttpServletResponse.SC_NOT_FOUND
import javax.xml.bind.DatatypeConverter

class RequestRouter : Filter {
  private lateinit var context: ServletContext

  override fun init(config: FilterConfig) {
    this.context = config.servletContext
    val velocityProps = Properties()
    velocityProps.setProperty("file.resource.loader.path", context.getRealPath("/WEB-INF/views"))
    velocityProps.setProperty("file.resource.loader.cache", "true")
    velocity = VelocityEngine(velocityProps)
    velocity.setApplicationAttribute("javax.servlet.ServletContext", context)
    velocity.init()
  }

  override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    req as HttpServletRequest
    res as HttpServletResponse
    val path = req.servletPath

    try {
      val by = req.getParameter("by")
      val random = req.getParameter("random")
      detectMobile(req)
      detectBot(by, random, req, res)

      val picasa = Picasa(by, req.getParameter("authkey"))
      req.setAttribute("picasa", picasa)
      req.setAttribute("host", req.getHeader("host"))
      req.setAttribute("servletPath", req.servletPath)

      if (req.getParameter("reload") != null) CacheReloader().reload()

      when {
        random != null -> renderRandom(picasa, random, req, res)
        path == null || "/" == path -> render("gallery", picasa.gallery, req, res)
        path.lastIndexOf('.') >= path.length - 4 -> chain.doFilter(req, res)
        else -> renderAlbum(path, picasa, req, res)
      }
    }
    catch (e: Redirect) {
      res.sendRedirect(e.path)
      res.status = SC_MOVED_PERMANENTLY
    }
    catch (e: MissingResourceException) {
      res.sendError(SC_NOT_FOUND)
    }
  }

  private fun renderAlbum(path: String, picasa: Picasa, request: HttpServletRequest, response: HttpServletResponse) {
    val parts = path.split("/")
    val album: Album
    try {
      album = picasa.getAlbum(parts[1])
    }
    catch (e: MissingResourceException) {
      album = picasa.search(parts[1])
      album.title = "Photos matching '" + parts[1] + "'"
      // TODO: no longer works for non-logged-in requests
    }

    if (parts.size > 2) {
      for (photo in album.photos) {
        if (photo.id == parts[2]) {
          request.setAttribute("photo", photo)
          break
        }
      }
    }
    render("album", album, request, response)
  }

  private fun renderRandom(picasa: Picasa, random: String, request: HttpServletRequest, response: HttpServletResponse) {
    request.setAttribute("delay", request.getParameter("delay"))
    if (request.getParameter("refresh") != null) request.setAttribute("refresh", true)
    render("random", picasa.getRandomPhotos(DatatypeConverter.parseInt(if (random.length > 0) random else "1")), request, response)
  }

  private fun detectMobile(request: HttpServletRequest) {
    val userAgent = request.getHeader("User-Agent")
    request.setAttribute("mobile", userAgent != null && userAgent.contains("Mobile") && !userAgent.contains("iPad") && !userAgent.contains("Tab"))
  }

  private fun detectBot(by: String?, random: String?, request: HttpServletRequest, response: HttpServletResponse) {
    val userAgent = request.getHeader("User-Agent")
    val bot = isBot(userAgent)
    if (bot && (by != null || random != null)) {
      throw Redirect("/")
    }
    request.setAttribute("bot", bot)
  }

  override fun destroy() { }

  companion object {
    private val logger = Logger.getLogger(RequestRouter::class.java.name)
    private lateinit var velocity: VelocityEngine

    internal fun isBot(userAgent: String?): Boolean {
      return userAgent == null || userAgent.toLowerCase().contains("bot/") || userAgent.contains("spider/")
    }

    internal fun render(template: String, source: Any?, request: HttpServletRequest, response: HttpServletResponse) {
      val start = System.currentTimeMillis()

      request.setAttribute(template, source)

      if (response.contentType == null)
        response.contentType = "text/html; charset=utf8"
      if (source is Entity && source.timestamp != null)
        response.addDateHeader("Last-Modified", source.timestamp!!)

      val ctx = VelocityContext()
      val attrs = request.attributeNames
      while (attrs.hasMoreElements()) {
        val name = attrs.nextElement()
        ctx.put(name, request.getAttribute(name))
      }
      val tmpl = velocity.getTemplate(template + ".vm")
      tmpl.merge(ctx, response.writer)

      logger.info("Rendered in " + (System.currentTimeMillis() - start) + " ms")
    }
  }
}

class Redirect(val path: String): Exception()
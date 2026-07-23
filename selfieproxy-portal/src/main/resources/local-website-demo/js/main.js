(function () {
  var toggle = document.getElementById("nav-toggle");
  var links = document.getElementById("nav-links");

  if (toggle && links) {
    toggle.addEventListener("click", function () {
      var isOpen = links.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", isOpen ? "true" : "false");
    });

    links.querySelectorAll("a").forEach(function (link) {
      link.addEventListener("click", function () {
        links.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
      });
    });
  }

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if ("IntersectionObserver" in window && !reduceMotion) {
    var revealItems = document.querySelectorAll(".reveal");
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -60px 0px" }
    );
    revealItems.forEach(function (item) {
      observer.observe(item);
    });
  } else {
    document.querySelectorAll(".reveal").forEach(function (item) {
      item.classList.add("is-visible");
    });
  }

  var lightbox = document.getElementById("lightbox");
  var lightboxImg = document.getElementById("lightbox-img");
  var lightboxClose = document.getElementById("lightbox-close");
  var triggers = document.querySelectorAll(".shot-trigger");

  if (lightbox && lightboxImg && lightboxClose && triggers.length) {
    var openLightbox = function (src, alt) {
      lightboxImg.src = src;
      lightboxImg.alt = alt || "";
      lightbox.hidden = false;
      document.body.style.overflow = "hidden";
      lightboxClose.focus();
    };

    var closeLightbox = function () {
      lightbox.hidden = true;
      lightboxImg.src = "";
      document.body.style.overflow = "";
    };

    triggers.forEach(function (trigger) {
      trigger.addEventListener("click", function () {
        openLightbox(trigger.getAttribute("data-lightbox-src"), trigger.getAttribute("data-lightbox-alt"));
      });
    });

    lightboxClose.addEventListener("click", closeLightbox);

    lightbox.addEventListener("click", function (event) {
      if (event.target === lightbox) {
        closeLightbox();
      }
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !lightbox.hidden) {
        closeLightbox();
      }
    });
  }

  var heroImg = document.getElementById("hero-carousel-img");
  var heroTrigger = document.getElementById("hero-carousel-trigger");
  var heroDotsWrap = document.getElementById("hero-carousel-dots");
  var heroShot = document.querySelector(".hero-shot");

  if (heroImg && heroTrigger && heroDotsWrap && heroShot) {
    var slides = [
      {
        src: "screenshots/edit-application.jpg",
        alt: "Edit application form for nas, showing the subdomain, the domain dropdown, the resulting HTTPS address, an authentication checkbox, and the homelab's protocol, host and port fields."
      },
      {
        src: "screenshots/homelabs.jpg",
        alt: "Homelabs page listing two connected networks, my-homelab shown Connected in green with 5 applications, and iot-gadgets shown Disconnected in red with 1 application."
      },
      {
        src: "screenshots/edit-homelab.jpg",
        alt: "Edit homelab page for iot-gadgets, showing its name, a masked secret, and a Docker Compose snippet for connecting the agent."
      },
      {
        src: "screenshots/rdp-windows.jpg?v=2",
        alt: "Browser-based remote desktop session titled windows-box (RDP), showing the Windows 10 Settings app open to About, with the Windows Specifications for the homelab machine, and the remote desktop's own taskbar at the bottom."
      },
      {
        src: "screenshots/rdp-ubuntu.jpg?v=2",
        alt: "Browser-based remote desktop session showing a Kubuntu desktop with LibreOffice Calc open."
      },
      {
        src: "screenshots/ssh-terminal.jpg?v=2",
        alt: "Browser-based SSH terminal session titled vibecoding-ssh, showing an Ubuntu welcome banner, system information, and a live command prompt."
      },
      {
        src: "screenshots/local-websites.jpg",
        alt: "Local websites page listing selfieproxy.online redirecting to https://www.selfieproxy.online, and www.selfieproxy.online itself, both temporarily flagged as using a self-signed certificate."
      }
    ];

    var dots = heroDotsWrap.querySelectorAll(".carousel-dot");
    var current = 0;
    var fadeTimer = null;
    var autoplayTimer = null;

    var showSlide = function (index) {
      current = (index + slides.length) % slides.length;
      var slide = slides[current];

      window.clearTimeout(fadeTimer);
      heroImg.classList.add("is-fading");
      fadeTimer = window.setTimeout(function () {
        heroImg.src = slide.src;
        heroImg.alt = slide.alt;
        heroTrigger.setAttribute("data-lightbox-src", slide.src);
        heroTrigger.setAttribute("data-lightbox-alt", slide.alt);
        heroImg.classList.remove("is-fading");
      }, 220);

      dots.forEach(function (dot, i) {
        dot.classList.toggle("is-active", i === current);
      });
    };

    var stopAutoplay = function () {
      if (autoplayTimer) {
        window.clearInterval(autoplayTimer);
        autoplayTimer = null;
      }
    };

    var startAutoplay = function () {
      if (reduceMotion) {
        return;
      }
      stopAutoplay();
      autoplayTimer = window.setInterval(function () {
        showSlide(current + 1);
      }, 4500);
    };

    dots.forEach(function (dot, i) {
      dot.addEventListener("click", function () {
        showSlide(i);
        startAutoplay();
      });
    });

    heroShot.addEventListener("mouseenter", stopAutoplay);
    heroShot.addEventListener("mouseleave", startAutoplay);
    heroShot.addEventListener("focusin", stopAutoplay);
    heroShot.addEventListener("focusout", startAutoplay);

    startAutoplay();
  }
})();

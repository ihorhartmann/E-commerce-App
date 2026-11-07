package middleware

import (
	"shippingservice/adapter/in/http/httperror"
	"shippingservice/domain/apperror"

	"github.com/gin-gonic/gin"
)

// handledErrorContextKey is where HandleControllerError leaves the error it has already written as
// the response, for the middleware above it (gin.Context).
const handledErrorContextKey = "handledError"

// Errors are written as responses at two points of the chain, both by httperror.Write:
//
//	Metrics -> otelgin -> HandleMiddlewareError -> Recover -> Logging -> Auth -> Idempotency -> HandleControllerError -> controller
//	                         |                                                                     writes errors of controllers (and
//	                         |                                                                     of NoRoute/NoMethod) right after them
//	                         writes errors of the middleware above HandleControllerError - auth, idempotency - and panics
//	                         Recover caught
//
// Gin handlers don't return an error: they add it to the context with c.Error, and the error
// handlers - ordinary middleware, unlike Fiber's ErrorHandler - write the last one after c.Next. A
// controller's error has to be written below the idempotency middleware, which needs the final
// response to decide whether to cache it. MetricsMiddleware and otelgin stay above both: they read
// the status from the written response (otelgin can't be told to look at c.Errors), so every error
// must be written by the time they get back from c.Next.

// HandleMiddlewareError must be the first of the error handling chain, where Fiber has its
// ErrorHandler: only MetricsMiddleware and otelgin are registered above it - they add no errors of
// their own. It writes the error left in the context by the middleware below it.
func HandleMiddlewareError(c *gin.Context) {
	c.Next()

	if len(c.Errors) == 0 || c.Writer.Written() {
		return
	}
	httperror.Write(c, c.Errors.Last().Err)
}

// HandleControllerError must be registered as the last middleware, right before the routes. It
// writes the error a controller left in the context - or a panic in it - as the response, records it
// for HandledError, and clears the context's errors: the error is handled, so the middleware above
// see a final response and nothing is left for HandleMiddlewareError.
func HandleControllerError(c *gin.Context) {
	defer func() {
		if recovered := recover(); recovered != nil {
			writeControllerError(c, apperror.FromPanic(recovered))
			c.Abort()
		}
	}()

	c.Next()

	if len(c.Errors) > 0 {
		writeControllerError(c, c.Errors.Last().Err)
	}
}

func writeControllerError(c *gin.Context, err error) {
	c.Set(handledErrorContextKey, err)
	c.Errors = c.Errors[:0]
	httperror.Write(c, err)
}

// HandledError returns the error HandleControllerError has already written as the response, or nil
// if the controller succeeded.
func HandledError(c *gin.Context) error {
	err, _ := c.Value(handledErrorContextKey).(error)
	return err
}

// Recover must be registered right below HandleMiddlewareError. It turns a panic anywhere in the
// middleware below it into an error for HandleMiddlewareError, instead of a crashed request. A panic
// in a controller is already caught lower, by HandleControllerError.
func Recover(c *gin.Context) {
	defer func() {
		if recovered := recover(); recovered != nil {
			_ = c.Error(apperror.FromPanic(recovered))
			c.Abort()
		}
	}()

	c.Next()
}

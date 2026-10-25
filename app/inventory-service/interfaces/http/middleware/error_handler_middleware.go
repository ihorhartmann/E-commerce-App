package middleware

import (
	"inventoryservice/domain/apperror"
	"inventoryservice/interfaces/http/httperror"

	"github.com/gofiber/fiber/v2"
)

// handledErrorLocalsKey is where HandleControllerError leaves the error it has already written as
// the response, for the middleware above it (fiber.Ctx.Locals).
const handledErrorLocalsKey = "handledError"

// Errors are written as responses at two points of the chain, both by httperror.Write:
//
//	Recover -> Logging -> Auth -> Idempotency -> HandleControllerError -> controller
//	   |                                               writes errors of controllers (and of the
//	   |                                               router) right after them
//	HandleMiddlewareError (fiber.Config.ErrorHandler)
//	   writes errors of the middleware above HandleControllerError - auth, idempotency - and panics
//	   Recover caught
//
// A controller's error has to be written below the idempotency middleware: Fiber calls its
// ErrorHandler only after the whole chain has returned, and the idempotency middleware needs the
// final response to decide whether to cache it.

// HandleMiddlewareError is the app's fiber.Config.ErrorHandler: it writes every error that reached
// the end of the chain.
func HandleMiddlewareError(c *fiber.Ctx, err error) error {
	return httperror.Write(c, err)
}

// HandleControllerError must be registered as the last middleware, right before the routes. It
// writes the error a controller returned - or a panic in it - as the response, records it for
// HandledError, and returns nil: the error is handled, so the middleware above see a final response
// and nothing is left for HandleMiddlewareError.
func HandleControllerError(c *fiber.Ctx) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = writeControllerError(c, apperror.FromPanic(recovered))
		}
	}()

	if nextErr := c.Next(); nextErr != nil {
		return writeControllerError(c, nextErr)
	}
	return nil
}

func writeControllerError(c *fiber.Ctx, err error) error {
	c.Locals(handledErrorLocalsKey, err)
	return httperror.Write(c, err)
}

// HandledError returns the error HandleControllerError has already written as the response, or nil
// if the controller succeeded.
func HandledError(c *fiber.Ctx) error {
	err, _ := c.Locals(handledErrorLocalsKey).(error)
	return err
}

// Recover must be registered as the first middleware. It turns a panic anywhere in the middleware
// below it into an error for HandleMiddlewareError, instead of a crashed request. A panic in a
// controller is already caught lower, by HandleControllerError.
func Recover(c *fiber.Ctx) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = apperror.FromPanic(recovered)
		}
	}()

	return c.Next()
}

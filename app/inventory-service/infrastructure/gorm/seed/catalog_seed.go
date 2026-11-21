// Package seed fills a database with the demo catalog, once per database.
//
// seed_history keeps a row for the applied catalog, written in the same transaction as its products, so a restart
// doesn't bring back a product an admin deleted, nor duplicate the catalog.
package seed

import (
	"log/slog"

	"inventoryservice/domain/values"
	"inventoryservice/infrastructure/gorm/seed/entity"
	productentity "inventoryservice/infrastructure/repository/entity"

	"github.com/shopspring/decimal"
	"gorm.io/gorm"
)

const catalogSeedName = "catalog-v1"

// lockKey is the Postgres advisory lock the seed runs under: replicas starting at the same time insert the catalog
// once, instead of both finding it missing.
const lockKey int64 = 7_020_093

// productImagesPath is where the web UI serves the product photos from (app/web-ui/src/jsMain/resources), relative to
// the page.
const productImagesPath = "images/products/"

type catalogProduct struct {
	name        string
	description string
	price       string
	quantity    uint
	image       string
}

// catalog is the demo shop's catalog: what the shop sold before it had photos, now with one each. The ids are left to
// the database.
var catalog = []catalogProduct{
	{"Aluminium Laptop Stand", "Six height levels, folds flat, fits laptops from 10 to 16 inches.", "34.50", 55, "laptop-stand.webp"},
	{"Desk Mat 90 x 40 cm", "Water-resistant surface, stitched edges, non-slip rubber base.", "19.90", 200, "desk-mat.webp"},
	{`27" 4K Monitor`, "IPS panel, 60 Hz, USB-C with 65 W power delivery, height-adjustable stand.", "349.00", 18, "monitor-27-4k.webp"},
	{"Noise-Cancelling Headphones", "Over-ear, active noise cancelling, 30 hours of battery, multipoint Bluetooth.", "179.00", 35, "noise-cancelling-headphones.webp"},
	{"Monitor Arm", "Gas-spring arm for one screen up to 32 inches and 9 kg, desk clamp or grommet.", "64.90", 38, "monitor-arm.webp"},
	{`24" Office Monitor`, "Full HD IPS, flicker-free, built-in speakers, VESA mount.", "139.00", 45, "office-monitor.webp"},
	{"USB Flash Drive 128 GB", "USB-C and USB-A on one metal stick, up to 400 MB/s.", "24.90", 130, "usb-flash-drive.webp"},
	{"Monitor Light Bar", "Sits on top of the screen, lights the desk without glare, wireless dial.", "49.00", 40, "monitor-light-bar.webp"},
	{"Vertical Ergonomic Mouse", "57-degree grip that relaxes the wrist, silent buttons, rechargeable.", "39.90", 42, "vertical-mouse.webp"},
	{"Ergonomic Wireless Mouse", "Silent clicks, 4000 DPI sensor, up to 70 days on one charge over USB-C.", "29.90", 140, "ergonomic-wireless-mouse.webp"},
	{"Mechanical Keyboard 75%", "Hot-swappable tactile switches, aluminium frame, wired and Bluetooth.", "89.00", 60, "mechanical-keyboard.webp"},
	{"Wireless Earbuds", "Active noise cancelling, 8 hours per charge, 32 with the case, IPX4.", "119.00", 70, "wireless-earbuds.webp"},
	{"Full HD Webcam", "1080p at 30 fps, autofocus, two microphones and a privacy shutter.", "49.90", 75, "webcam.webp"},
	{"USB-C Hub 7-in-1", "HDMI 4K, two USB-A, SD and microSD, 100 W pass-through charging.", "39.90", 120, "usb-c-hub.webp"},
	{"Portable SSD 1 TB", "Up to 1050 MB/s over USB-C, drop-resistant aluminium case.", "99.00", 40, "portable-ssd.webp"},
	{"65 W GaN Charger", "Two USB-C and one USB-A port, folding plug, charges a laptop and a phone at once.", "44.90", 90, "gan-charger.webp"},
	{"Bluetooth Speaker", "Waterproof (IP67), 12 hours of playback, pairs with a second speaker for stereo.", "59.00", 0, "bluetooth-speaker.webp"},
	{`34" Ultrawide Monitor`, "Curved VA panel, 3440 x 1440 at 144 Hz, USB-C with 90 W power delivery.", "529.00", 12, "ultrawide-monitor.webp"},
	{"USB Condenser Microphone", "Cardioid pattern, 24-bit / 96 kHz, mute button and headphone output.", "89.90", 28, "usb-microphone.webp"},
	{`Ring Light 10"`, "Three colour temperatures, ten brightness levels, desk tripod and phone holder.", "29.90", 60, "ring-light.webp"},
	{"Wireless Keyboard Slim", "Low-profile scissor keys, pairs with three devices, two AAA batteries for a year.", "49.90", 85, "wireless-keyboard-slim.webp"},
	{"Gaming Mouse", "26 000 DPI optical sensor, 58 g, braided cable, five programmable buttons.", "59.90", 50, "gaming-mouse.webp"},
	{"Numeric Keypad", "Wireless numpad with a calculator key, 2.4 GHz receiver included.", "22.50", 65, "numeric-keypad.webp"},
	{"Wrist Rest Set", "Memory foam rests for the keyboard and the mouse, non-slip base.", "18.90", 110, "wrist-rest.webp"},
	{"External SSD 2 TB", "Up to 2000 MB/s over USB 3.2 Gen 2x2, pocket-sized, hardware encryption.", "169.00", 22, "external-ssd.webp"},
	{"microSD Card 256 GB", "A2, V30, up to 190 MB/s read, with an SD adapter.", "27.90", 150, "microsd-card.webp"},
	{"NAS 2-Bay", "Network storage for two drives, RAID 1, 2.5 GbE, automatic photo backup.", "299.00", 9, "nas.webp"},
	{"Wi-Fi 6 Router", "Dual band AX3000, four gigabit ports, WPA3, app setup.", "99.00", 30, "wifi-router.webp"},
	{"Mesh Wi-Fi System, 3 Pack", "Covers up to 450 m², seamless roaming, Ethernet port on every unit.", "249.00", 14, "mesh-wifi.webp"},
	{"Gigabit Switch 8-Port", "Unmanaged, fanless metal housing, plug and play.", "29.90", 55, "gigabit-switch.webp"},
	{"Cat 6 Ethernet Cable 5 m", "Flat, snagless connectors, up to 10 Gbit/s over short runs.", "9.90", 240, "ethernet-cable.webp"},
	{"USB-C Cable 2 m, 100 W", "Braided, USB 2.0 data, e-marker chip for 100 W charging.", "14.90", 260, "usb-c-cable.webp"},
	{"Power Bank 20 000 mAh", "65 W USB-C output, charges a laptop, two ports at once.", "54.90", 48, "power-bank.webp"},
	{"Wireless Charging Pad", "15 W Qi2 with magnetic alignment, USB-C cable included.", "34.90", 75, "wireless-charging-pad.webp"},
	{"Smart Plug, 2 Pack", "Wi-Fi plug with energy monitoring and schedules, works with voice assistants.", "24.90", 90, "smart-plug.webp"},
	{"LED Desk Lamp", "Adjustable colour temperature, USB-A charging port, touch dimmer.", "44.90", 36, "led-desk-lamp.webp"},
	{`Laptop Backpack 15.6"`, "Water-resistant, padded laptop sleeve, luggage strap, 22 litres.", "69.00", 33, "laptop-backpack.webp"},
	{`Laptop Sleeve 14"`, "Felt sleeve with a magnetic flap and a pocket for the charger.", "24.90", 80, "laptop-sleeve.webp"},
	{"Docking Station", "Two 4K displays, 2.5 GbE, five USB ports, 96 W charging over one USB-C cable.", "189.00", 16, "docking-station.webp"},
	{"Bluetooth Tracker, 4 Pack", "Find keys and bags from the phone, replaceable battery for a year.", "79.00", 27, "bluetooth-tracker.webp"},
	{"Smartwatch", "AMOLED display, heart rate and SpO2, GPS, 10 days of battery.", "149.00", 24, "smartwatch.webp"},
	{"Fitness Band", "Sleep and step tracking, 14 days of battery, water-resistant to 50 m.", "39.90", 68, "fitness-band.webp"},
	{"E-Book Reader", `6.8" glare-free display, warm light, 16 GB, waterproof.`, "139.00", 19, "e-book-reader.webp"},
	{"Graphics Tablet", "10 x 6 inch active area, battery-free pen with 8192 pressure levels.", "69.90", 21, "graphics-tablet.webp"},
	{"Webcam Cover, 3 Pack", "Slim sliding covers for laptops, tablets and phones.", "6.90", 4, "webcam-cover.webp"},
}

// Apply inserts the demo catalog unless this database already has it. It runs after the schema migration, which
// creates the seed_history table.
func Apply(db *gorm.DB) error {
	applied := false
	err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec("SELECT pg_advisory_xact_lock(?)", lockKey).Error; err != nil {
			return err
		}

		var count int64
		if err := tx.Model(&entity.SeedHistory{}).Where("name = ?", catalogSeedName).Count(&count).Error; err != nil {
			return err
		}
		if count > 0 {
			return nil
		}

		if err := applyCatalog(tx); err != nil {
			return err
		}
		applied = true
		return tx.Create(&entity.SeedHistory{Name: catalogSeedName}).Error
	})
	if err != nil {
		return ErrSeedFailure.
			WithInternalMessage("apply seed").
			WithAttrs(slog.String("seed", catalogSeedName)).
			WithCause(err)
	}

	if applied {
		slog.Info("seed applied", slog.String("seed", catalogSeedName))
	}
	return nil
}

func applyCatalog(tx *gorm.DB) error {
	rows := make([]*productentity.Product, 0, len(catalog))
	for _, product := range catalog {
		imageURL := productImagesPath + product.image
		rows = append(rows, &productentity.Product{
			Name:          product.name,
			Description:   product.description,
			ImageURL:      &imageURL,
			PriceAmount:   decimal.RequireFromString(product.price),
			PriceCurrency: string(values.EUR),
			Quantity:      product.quantity,
		})
	}

	return tx.Create(rows).Error
}

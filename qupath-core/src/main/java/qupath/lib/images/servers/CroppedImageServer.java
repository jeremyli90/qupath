package qupath.lib.images.servers;

import java.awt.image.BufferedImage;
import java.io.IOException;

import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * ImageServer that treats a particular sub-region of another ImageServer as a full image, 
 * i.e. it performs dynamic cropping, without a need to export the cropped region separately.
 * 
 * @author Pete Bankhead
 *
 */
public class CroppedImageServer extends WrappedImageServer<BufferedImage> {
	
	private ImageServerMetadata metadata;
	
	private ImageRegion region;

	protected CroppedImageServer(final ImageServer<BufferedImage> server, ImageRegion region) {
		super(server);
		this.region = region;
		
		var levelBuilder = new ImageServerMetadata.ImageResolutionLevel.Builder(region.getWidth(), region.getHeight());
		boolean fullServer = server.getWidth() == region.getWidth() && server.getHeight() == region.getHeight();
		int i = 0;
		do {
			var originalLevel = server.getMetadata().getLevel(i);
			if (fullServer)
				levelBuilder.addLevel(originalLevel);
			else
				levelBuilder.addLevelByDownsample(originalLevel.getDownsample());
			i++;
		} while (i < server.nResolutions() && region.getWidth() >= server.getPreferredTileWidth() && region.getHeight() >= server.getPreferredTileHeight());
		
		metadata = new ImageServerMetadata.Builder(getClass(), server.getMetadata())
				.path(server.getPath() + ": Cropped " + region.toString())
				.width(region.getWidth())
				.height(region.getHeight())
				.name(String.format("%s (%d, %d, %d, %d)", server.getMetadata().getName(), region.getX(), region.getY(), region.getWidth(), region.getHeight()))
				.levels(levelBuilder.build())
				.build();
	}
	
	
	@Override
	public BufferedImage readBufferedImage(final RegionRequest request) throws IOException {
		RegionRequest request2 = RegionRequest.createInstance(
				request.getPath(), request.getDownsample(),
				request.getX() + region.getX(),
				request.getY() + region.getY(),
				request.getWidth(), request.getHeight());
		BufferedImage img = getWrappedServer().readBufferedImage(request2);
		// TODO: Mask as ellipse, if necessary?
		return img;
	}
	
	
	public ImageServerMetadata getOriginalMetadata() {
		return metadata;
	}
	
	
	@Override
	public String getServerType() {
		return "Cropped image server";
	}

}
